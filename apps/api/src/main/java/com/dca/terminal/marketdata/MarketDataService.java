package com.dca.terminal.marketdata;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.instrument.InstrumentType;
import com.dca.terminal.marketdata.MarketDataEntities.PriceDailyEntity;
import com.dca.terminal.marketdata.MarketDataEntities.QuoteLatestEntity;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import com.dca.terminal.settings.AppSettingEntity;
import com.dca.terminal.settings.AppSettingRepository;
import com.dca.terminal.marketdata.ProviderModels.EtfProfile;
import com.dca.terminal.marketdata.ProviderModels.IntradayBar;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.ProviderModels.ProviderQuote;
import com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;
import com.dca.terminal.marketdata.ProviderModels.SplitEvent;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.instrument.InstrumentDtos.InstrumentResponse;
import static com.dca.terminal.instrument.InstrumentDtos.SearchResult;
import static com.dca.terminal.instrument.InstrumentDtos.QuoteResponse;
import static com.dca.terminal.marketdata.MarketDataDtos.MetricsResponse;
import static com.dca.terminal.marketdata.MarketDataDtos.PricePoint;
import static com.dca.terminal.marketdata.MarketDataDtos.PriceHistoryResponse;
import static com.dca.terminal.marketdata.MarketDataDtos.SyncResponse;

@Service
public class MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private final InstrumentRepository instrumentRepository;
    private final PriceDailyRepository priceRepository;
    private final QuoteLatestRepository quoteRepository;
    private final SplitEventRepository splitRepository;
    private final FundNavDailyRepository navRepository;
    private final AppSettingRepository settingRepository;
    private final CanonicalEtfCatalog canonicalEtfCatalog = new CanonicalEtfCatalog();
    private final EnumMap<ProviderId, MarketDataProvider> providers = new EnumMap<>(ProviderId.class);
    private final ProviderId configuredPrimary;
    private final ProviderId configuredFallback;
    private final Clock clock;
    private final ZoneId zone;
    private final Duration quoteTtl;
    private final int providerAttempts;

    public MarketDataService(InstrumentRepository instrumentRepository,
                             PriceDailyRepository priceRepository,
                             QuoteLatestRepository quoteRepository,
                             SplitEventRepository splitRepository,
                             FundNavDailyRepository navRepository,
                             AppSettingRepository settingRepository,
                             List<MarketDataProvider> providerList,
                             Clock clock,
                             ZoneId applicationZone,
                             @Value("${dca.market-data.primary:YAHOO}") String primary,
                             @Value("${dca.market-data.fallback:TWELVE_DATA}") String fallback,
                             @Value("${dca.market-data.quote-ttl-seconds:60}") long quoteTtlSeconds,
                             @Value("${dca.market-data.provider-attempts:2}") int providerAttempts) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.quoteRepository = quoteRepository;
        this.splitRepository = splitRepository;
        this.navRepository = navRepository;
        this.settingRepository = settingRepository;
        providerList.forEach(provider -> providers.put(provider.id(), provider));
        this.configuredPrimary = parseProvider(primary, ProviderId.YAHOO, false);
        this.configuredFallback = parseProvider(fallback, ProviderId.TWELVE_DATA, true);
        this.clock = clock;
        this.zone = applicationZone;
        this.quoteTtl = Duration.ofSeconds(quoteTtlSeconds);
        this.providerAttempts = Math.max(1, providerAttempts);
    }

    @Transactional(readOnly = true)
    public List<InstrumentResponse> tracked() {
        return instrumentRepository.findAllByTrackedTrueOrderBySymbolAsc().stream().map(this::toResponse).toList();
    }

    @Cacheable(cacheNames = "search", key = "#query == null ? '' : #query.toUpperCase()")
    public List<SearchResult> search(String query) {
        String normalized = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() < 1 || normalized.length() > 32) return List.of();
        List<ProviderSearchResult> canonicalMatches = canonicalEtfCatalog.search(normalized);
        try {
            List<ProviderSearchResult> results = callWithProvider(provider -> provider.search(normalized)).value();
            return toSearchResults(mergeSearchResults(results, canonicalMatches));
        } catch (ProviderException exception) {
            if (!canonicalMatches.isEmpty()) {
                log.warn("market ETF search degraded provider={} fallback=CANONICAL_CATALOG matches={}",
                        exception.provider(), canonicalMatches.size());
                return toSearchResults(canonicalMatches);
            }
            log.warn("market ETF search unavailable provider={} reason={}", exception.provider(), exception.getMessage());
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE",
                    "ETF search is temporarily unavailable");
        }
    }

    @Transactional
    public InstrumentEntity add(String ticker) {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (!symbol.matches("[A-Z][A-Z0-9.-]{0,15}")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TICKER", "Ticker must be a US ETF symbol");
        }
        Optional<InstrumentEntity> existing = instrumentRepository.findBySymbolIgnoreCase(symbol);
        if (existing.isPresent()) {
            InstrumentEntity instrument = existing.get();
            instrument.setTracked(true);
            // Re-adding an ETF is also an explicit recovery action for an
            // earlier failed or incomplete initial history sync.
            if (instrument.getDataStatus() != FreshnessStatus.FRESH) {
                sync(instrument);
            }
            return instrument;
        }
        List<ProviderSearchResult> found;
        ProviderCall<List<ProviderSearchResult>> providerCall = null;
        try {
            providerCall = callWithProvider(provider -> provider.search(symbol));
            found = mergeSearchResults(providerCall.value(), canonicalEtfCatalog.search(symbol));
        } catch (ProviderException exception) {
            Optional<ProviderSearchResult> canonical = canonicalEtfCatalog.findExact(symbol);
            if (canonical.isEmpty()) {
                throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE",
                        "ETF could not be confirmed because the market data provider is unavailable");
            }
            log.warn("market ETF confirmation degraded ticker={} provider={} fallback=CANONICAL_CATALOG",
                    symbol, exception.provider());
            found = List.of(canonical.get());
        }
        ProviderSearchResult match = (found == null ? List.<ProviderSearchResult>of() : found).stream()
                .filter(item -> symbol.equalsIgnoreCase(item.symbol()) && item.type() == InstrumentType.ETF)
                .findFirst()
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "ETF_NOT_CONFIRMED",
                        "Provider search did not confirm an ETF with ticker " + symbol));
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(symbol);
        instrument.setName(match.name() == null || match.name().isBlank() ? symbol : match.name());
        instrument.setExchange(match.exchange());
        instrument.setCurrency("USD");
        instrument.setInstrumentType(InstrumentType.ETF);
        boolean providerConfirmed = providerCall != null && containsExactEtf(providerCall.value(), symbol);
        instrument.setDataProvider(providerConfirmed ? providerCall.provider().name() : "CANONICAL_CATALOG");
        instrument.setDataStatus(FreshnessStatus.INSUFFICIENT_HISTORY);
        InstrumentEntity saved = instrumentRepository.save(instrument);
        refreshProfile(saved);
        SyncResponse initialSync = sync(saved);
        if (initialSync.status() != FreshnessStatus.FRESH) {
            log.warn("initial market history sync incomplete ticker={} status={}", saved.getSymbol(), initialSync.status());
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public InstrumentEntity getInstrument(String symbol) {
        return instrumentRepository.findBySymbolIgnoreCase(symbol)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND", "ETF not found: " + symbol));
    }

    @Transactional
    public InstrumentEntity untrack(String symbol) {
        InstrumentEntity instrument = getInstrument(symbol);
        instrument.setTracked(false);
        return instrument;
    }

    @Cacheable(cacheNames = "quotes", key = "#instrument.symbol")
    @Transactional
    public QuoteResponse latestQuote(InstrumentEntity instrument) {
        Instant now = clock.instant();
        Optional<QuoteLatestEntity> cached = quoteRepository.findById(instrument.getId());
        if (cached.isPresent() && cached.get().getRetrievedAt() != null
                && Duration.between(cached.get().getRetrievedAt(), now).compareTo(quoteTtl) <= 0
                && cached.get().getStatus() == FreshnessStatus.FRESH) {
            return toQuoteResponse(instrument, cached.get());
        }
        try {
            ProviderCall<ProviderQuote> result = callWithProvider(provider -> provider.getLatestQuote(instrument));
            ProviderQuote quote = result.value();
            if (quote.price() == null || quote.price().signum() <= 0) {
                throw new ProviderException(result.provider(), "Provider returned no valid quote", false);
            }
            QuoteLatestEntity entity = cached.orElseGet(QuoteLatestEntity::new);
            entity.setInstrumentId(instrument.getId());
            entity.setPrice(quote.price());
            entity.setPreviousClose(quote.previousClose());
            entity.setChange(quote.previousClose() == null ? null : quote.price().subtract(quote.previousClose(), DecimalMath.MC));
            entity.setChangePercent(quote.previousClose() == null || quote.previousClose().signum() == 0 ? null
                    : quote.price().divide(quote.previousClose(), new MathContext(34)).subtract(BigDecimal.ONE, DecimalMath.MC));
            entity.setBid(quote.bid());
            entity.setAsk(quote.ask());
            entity.setMarketTimestamp(quote.marketTimestamp());
            entity.setRetrievedAt(quote.retrievedAt() == null ? now : quote.retrievedAt());
            entity.setSource(result.provider().name());
            entity.setStatus(FreshnessStatus.FRESH);
            quoteRepository.save(entity);
            return toQuoteResponse(instrument, entity);
        } catch (ProviderException exception) {
            if (cached.isPresent() && cached.get().getPrice() != null) {
                cached.get().setStatus(FreshnessStatus.STALE);
                quoteRepository.save(cached.get());
                return toQuoteResponse(instrument, cached.get());
            }
            QuoteLatestEntity unavailable = cached.orElseGet(QuoteLatestEntity::new);
            unavailable.setInstrumentId(instrument.getId());
            unavailable.setRetrievedAt(now);
            unavailable.setSource(exception.provider().name());
            unavailable.setStatus(FreshnessStatus.UNAVAILABLE);
            quoteRepository.save(unavailable);
            return toQuoteResponse(instrument, unavailable);
        }
    }

    @Transactional
    public SyncResponse sync(InstrumentEntity instrument) {
        LocalDate today = LocalDate.now(clock.withZone(currentZone()));
        LocalDate fiveYearsAgo = today.minusYears(5);
        Optional<PriceDailyEntity> latestStored = priceRepository.findTopByInstrumentIdOrderByTradeDateDesc(instrument.getId());
        LocalDate from = latestStored.map(entity -> entity.getTradeDate().plusDays(1)).orElse(fiveYearsAgo);
        if (from.isAfter(today)) {
            instrument.setDataStatus(FreshnessStatus.FRESH);
            instrumentRepository.save(instrument);
            return new SyncResponse(instrument.getSymbol(), 0, 0, FreshnessStatus.FRESH, clock.instant(), null);
        }
        try {
            ProviderCall<List<PriceBar>> result = callWithProvider(provider -> provider.getHistoricalPrices(instrument, from, today));
            LocalDate latestAvailable = latestStored.map(PriceDailyEntity::getTradeDate).orElse(null);
            int saved = 0;
            for (PriceBar bar : result.value()) {
                PriceDailyEntity entity = priceRepository.findByInstrumentIdAndTradeDateAndSource(
                        instrument.getId(), bar.tradeDate(), result.provider().name()).orElseGet(PriceDailyEntity::new);
                entity.setInstrument(instrument);
                entity.setTradeDate(bar.tradeDate());
                entity.setOpen(bar.open());
                entity.setHigh(bar.high());
                entity.setLow(bar.low());
                entity.setClose(bar.close());
                entity.setAdjustedClose(bar.adjustedClose());
                entity.setVolume(bar.volume());
                entity.setSource(result.provider().name());
                priceRepository.save(entity);
                saved++;
                if (latestAvailable == null || bar.tradeDate().isAfter(latestAvailable)) latestAvailable = bar.tradeDate();
            }
            int splits = syncSplits(instrument, providers.get(result.provider()), from, today);
            FreshnessStatus status = latestAvailable == null
                    ? FreshnessStatus.INSUFFICIENT_HISTORY : dailyFreshnessStatus(latestAvailable, today);
            instrument.setDataStatus(status);
            instrumentRepository.save(instrument);
            log.info("market sync completed ticker={} source={} rows={} splits={} status={}",
                    instrument.getSymbol(), result.provider(), saved, splits, status);
            return new SyncResponse(instrument.getSymbol(), saved, splits, status, clock.instant(),
                    syncMessage(status));
        } catch (ProviderException exception) {
            FreshnessStatus status = latestStored.isPresent() ? FreshnessStatus.STALE : FreshnessStatus.UNAVAILABLE;
            instrument.setDataStatus(status);
            instrumentRepository.save(instrument);
            log.warn("market sync unavailable ticker={} provider={} status={} reason={}",
                    instrument.getSymbol(), exception.provider(), status, exception.getMessage());
            return new SyncResponse(instrument.getSymbol(), 0, 0, status, clock.instant(),
                    status == FreshnessStatus.STALE
                            ? "Historical sync failed; existing data was retained"
                            : "Historical data is temporarily unavailable; retry when the provider recovers");
        }
    }

    /**
     * Re-fetches the bounded local history without deleting rows first. This is
     * an explicit repair operation for provider-adjusted fields, not the daily
     * incremental scheduler path.
     */
    @Transactional
    public SyncResponse fullResync(InstrumentEntity instrument) {
        LocalDate today = LocalDate.now(clock.withZone(currentZone()));
        LocalDate fiveYearsAgo = today.minusYears(5);
        Optional<PriceDailyEntity> latestStored = priceRepository.findTopByInstrumentIdOrderByTradeDateDesc(instrument.getId());
        try {
            ProviderCall<List<PriceBar>> result = callWithProvider(
                    provider -> provider.getHistoricalPrices(instrument, fiveYearsAgo, today));
            List<PriceBar> bars = result.value() == null ? List.of() : result.value().stream()
                    .filter(bar -> bar != null && bar.tradeDate() != null
                            && !bar.tradeDate().isBefore(fiveYearsAgo) && !bar.tradeDate().isAfter(today)
                            && bar.close() != null)
                    .toList();
            if (bars.isEmpty()) {
                FreshnessStatus status = latestStored.isPresent()
                        ? FreshnessStatus.STALE : FreshnessStatus.INSUFFICIENT_HISTORY;
                instrument.setDataStatus(status);
                instrumentRepository.save(instrument);
                return new SyncResponse(instrument.getSymbol(), 0, 0, status, clock.instant(), syncMessage(status));
            }

            MarketDataProvider provider = providers.get(result.provider());
            List<SplitEvent> splitEvents = provider.getSplits(instrument, fiveYearsAgo, today);
            if (splitEvents == null) {
                throw new ProviderException(result.provider(), "Provider returned no split response", false);
            }

            int saved = 0;
            LocalDate latestAvailable = null;
            for (PriceBar bar : bars) {
                PriceDailyEntity entity = priceRepository.findByInstrumentIdAndTradeDateAndSource(
                        instrument.getId(), bar.tradeDate(), result.provider().name()).orElseGet(PriceDailyEntity::new);
                entity.setInstrument(instrument);
                entity.setTradeDate(bar.tradeDate());
                entity.setOpen(bar.open());
                entity.setHigh(bar.high());
                entity.setLow(bar.low());
                entity.setClose(bar.close());
                entity.setAdjustedClose(bar.adjustedClose());
                entity.setVolume(bar.volume());
                entity.setSource(result.provider().name());
                priceRepository.save(entity);
                saved++;
                if (latestAvailable == null || bar.tradeDate().isAfter(latestAvailable)) latestAvailable = bar.tradeDate();
            }
            int splits = saveSplits(instrument, result.provider(), splitEvents);
            FreshnessStatus status = latestAvailable == null
                    ? FreshnessStatus.INSUFFICIENT_HISTORY : dailyFreshnessStatus(latestAvailable, today);
            instrument.setDataStatus(status);
            instrumentRepository.save(instrument);
            log.info("market full resync completed ticker={} source={} rows={} splits={} status={}",
                    instrument.getSymbol(), result.provider(), saved, splits, status);
            return new SyncResponse(instrument.getSymbol(), saved, splits, status, clock.instant(), syncMessage(status));
        } catch (ProviderException exception) {
            FreshnessStatus status = latestStored.isPresent() ? FreshnessStatus.STALE : FreshnessStatus.UNAVAILABLE;
            instrument.setDataStatus(status);
            instrumentRepository.save(instrument);
            log.warn("market full resync unavailable ticker={} provider={} status={} reason={}",
                    instrument.getSymbol(), exception.provider(), status, exception.getMessage());
            return new SyncResponse(instrument.getSymbol(), 0, 0, status, clock.instant(),
                    status == FreshnessStatus.STALE
                            ? "Full historical sync failed; existing data was retained"
                            : "Full historical data is temporarily unavailable; retry when the provider recovers");
        }
    }

    private String syncMessage(FreshnessStatus status) {
        if (status == FreshnessStatus.FRESH) return null;
        if (status == FreshnessStatus.STALE) return "Historical data is delayed";
        if (status == FreshnessStatus.INSUFFICIENT_HISTORY) {
            return "The provider returned no usable daily bars yet";
        }
        return "Historical data is temporarily unavailable; retry when the provider recovers";
    }

    @Transactional(readOnly = true)
    public PriceHistoryResponse prices(InstrumentEntity instrument, String range) {
        LocalDate today = LocalDate.now(clock.withZone(currentZone()));
        if ("1D".equalsIgnoreCase(range)) {
            try {
                ProviderCall<List<IntradayBar>> result = callWithProvider(provider -> provider.getIntradayPrices(instrument, today, today));
                List<PricePoint> points = result.value().stream()
                        .map(bar -> new PricePoint(bar.timestamp().toString(), bar.close(), null)).toList();
                return new PriceHistoryResponse(points, points.isEmpty() ? FreshnessStatus.UNAVAILABLE : FreshnessStatus.FRESH,
                        result.provider().name(), points.isEmpty() ? null : today, clock.instant(),
                        points.isEmpty() ? "Intraday data is unavailable" : null);
            } catch (ProviderException exception) {
                return new PriceHistoryResponse(List.of(), FreshnessStatus.UNAVAILABLE, exception.provider().name(),
                        null, clock.instant(), "Intraday data is unavailable");
            }
        }
        LocalDate from = rangeStart(today, range);
        List<PriceDailyEntity> rows = canonicalPrices(priceRepository
                .findAllByInstrumentIdAndTradeDateBetweenOrderByTradeDateAsc(instrument.getId(), from, today));
        FreshnessStatus status = rows.isEmpty() ? FreshnessStatus.INSUFFICIENT_HISTORY
                : dailyFreshnessStatus(rows.get(rows.size() - 1).getTradeDate(), today);
        String message = switch (status) {
            case STALE -> "Historical data is delayed";
            case INSUFFICIENT_HISTORY -> "Historical data is unavailable; retry the sync when the provider recovers";
            default -> null;
        };
        return new PriceHistoryResponse(rows.stream()
                .map(entity -> new PricePoint(entity.getTradeDate().toString(), entity.getClose(), entity.getAdjustedClose())).toList(),
                status, rows.isEmpty() ? null : rows.get(rows.size() - 1).getSource(),
                rows.isEmpty() ? null : rows.get(rows.size() - 1).getTradeDate(), clock.instant(),
                message);
    }

    @Transactional(readOnly = true)
    public MetricsResponse metrics(InstrumentEntity instrument) {
        LocalDate today = LocalDate.now(clock.withZone(currentZone()));
        List<PriceBar> bars = canonicalPrices(priceRepository.findAllByInstrumentIdAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
                        instrument.getId(), today.minusYears(5))).stream().map(this::toPriceBar).toList();
        ProviderQuote quote = null;
        Optional<QuoteLatestEntity> latest = quoteRepository.findById(instrument.getId());
        FreshnessStatus quoteStatus = latest.map(QuoteLatestEntity::getStatus).orElse(FreshnessStatus.STALE);
        if (latest.isPresent() && latest.get().getPrice() != null) {
            quote = new ProviderQuote(latest.get().getPrice(), latest.get().getPreviousClose(), latest.get().getBid(),
                    latest.get().getAsk(), latest.get().getMarketTimestamp(), latest.get().getRetrievedAt());
        }
        MarketMetricsCalculator.Metrics result = MarketMetricsCalculator.calculate(bars, quote, today, quoteStatus);
        FreshnessStatus status = result.status() == FreshnessStatus.FRESH && !bars.isEmpty()
                ? dailyFreshnessStatus(bars.get(bars.size() - 1).tradeDate(), today) : result.status();
        return new MetricsResponse(result.oneDay(), result.oneMonth(), result.threeMonths(), result.ytd(), result.oneYear(),
                result.threeYearCagr(), result.fiftyTwoWeekHigh(), result.fiftyTwoWeekLow(), result.currentDrawdown(),
                result.maxDrawdown1Y(), status, bars.isEmpty() ? null : bars.get(bars.size() - 1).tradeDate());
    }

    @Transactional
    public void refreshProfile(InstrumentEntity instrument) {
        try {
            Optional<EtfProfile> profile = this.<Optional<EtfProfile>>callWithProvider(
                    provider -> provider.getProfile(instrument)).value();
            profile.ifPresent(value -> {
                if (value.name() != null && !value.name().isBlank()) instrument.setName(value.name());
                if (value.exchange() != null) instrument.setExchange(value.exchange());
                if (value.issuer() != null) instrument.setIssuer(value.issuer());
                instrument.setExpenseRatio(value.expenseRatio());
                instrument.setAum(value.aum());
                instrument.setDividendYield(value.dividendYield());
                instrumentRepository.save(instrument);
            });
        } catch (ProviderException ignored) {
            log.debug("ETF profile unavailable for {}", instrument.getSymbol());
        }
    }

    public List<MarketDataDtos.ProviderStatus> providerStatuses() {
        return providers.values().stream().map(provider -> new MarketDataDtos.ProviderStatus(provider.id().name(), provider.isConfigured())).toList();
    }

    public String primaryProvider() { return providerSelection().primary().name(); }

    public String fallbackProvider() {
        return providerSelection().fallback() == null ? "NONE" : providerSelection().fallback().name();
    }

    public List<ProviderId> providerPriority() {
        ProviderSelection selection = providerSelection();
        return ProviderPriority.ordered(selection.primary(), selection.fallback());
    }

    private int syncSplits(InstrumentEntity instrument, MarketDataProvider provider, LocalDate from, LocalDate to) {
        return saveSplits(instrument, provider.id(), provider.getSplits(instrument, from, to));
    }

    private int saveSplits(InstrumentEntity instrument, ProviderId providerId, List<SplitEvent> events) {
        int saved = 0;
        List<ProviderId> priority = providerPriority();
        for (SplitEvent event : events) {
            SplitEventEntity entity = splitRepository.findByInstrumentIdAndEffectiveDate(
                    instrument.getId(), event.effectiveDate()).orElseGet(SplitEventEntity::new);
            if (entity.getId() != null
                    && ProviderPriority.rank(entity.getSource(), priority)
                    < ProviderPriority.rank(providerId.name(), priority)) {
                continue;
            }
            entity.setInstrument(instrument);
            entity.setEffectiveDate(event.effectiveDate());
            entity.setNumerator(event.numerator());
            entity.setDenominator(event.denominator());
            entity.setSource(providerId.name());
            splitRepository.save(entity);
            saved++;
        }
        return saved;
    }

    private QuoteResponse toQuoteResponse(InstrumentEntity instrument, QuoteLatestEntity entity) {
        Optional<FundNavDailyEntity> nav = navRepository.findTopByInstrumentIdOrderByNavDateDesc(instrument.getId());
        return new QuoteResponse(instrument.getSymbol(), entity.getPrice(), entity.getPreviousClose(), entity.getChange(),
                entity.getChangePercent(), entity.getBid(), entity.getAsk(), entity.getMarketTimestamp(),
                entity.getRetrievedAt(), entity.getSource(), entity.getStatus(),
                nav.map(FundNavDailyEntity::getNav).orElse(null), nav.map(FundNavDailyEntity::getNavDate).orElse(null));
    }

    private InstrumentResponse toResponse(InstrumentEntity entity) {
        return new InstrumentResponse(entity.getId(), entity.getSymbol(), entity.getName(), entity.getExchange(),
                entity.getCurrency(), entity.getInstrumentType(), entity.getIssuer(), entity.getExpenseRatio(),
                entity.getAum(), entity.getDividendYield(), entity.getDataProvider(), entity.isTracked(), entity.getDataStatus());
    }

    private PriceBar toPriceBar(PriceDailyEntity entity) {
        return new PriceBar(entity.getTradeDate(), entity.getOpen(), entity.getHigh(), entity.getLow(),
                entity.getClose(), entity.getAdjustedClose(), entity.getVolume());
    }

    private LocalDate rangeStart(LocalDate today, String range) {
        if (range == null) return today.minusYears(1);
        return switch (range.toUpperCase()) {
            case "1W" -> today.minusWeeks(1);
            case "1M" -> today.minusMonths(1);
            case "3M" -> today.minusMonths(3);
            case "YTD" -> LocalDate.of(today.getYear(), 1, 1);
            case "1Y" -> today.minusYears(1);
            case "3Y" -> today.minusYears(3);
            case "5Y", "ALL" -> today.minusYears(5);
            default -> throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE",
                    "Unsupported price range: " + range);
        };
    }

    private FreshnessStatus dailyFreshnessStatus(LocalDate latestDate, LocalDate today) {
        return latestDate == null || latestDate.isBefore(MarketCalendar.latestExpectedTradingDate(today))
                ? FreshnessStatus.STALE : FreshnessStatus.FRESH;
    }

    private ProviderId parseProvider(String value, ProviderId fallback, boolean allowNone) {
        if (value != null && allowNone && "NONE".equalsIgnoreCase(value.trim())) return null;
        try { return ProviderId.valueOf(value.trim().toUpperCase().replace('-', '_')); }
        catch (Exception ignored) { return fallback; }
    }

    private <T> T callWithFallback(Function<MarketDataProvider, T> operation, T defaultValue) {
        try {
            return callWithProvider(operation).value();
        } catch (ProviderException exception) {
            log.warn("all market data providers failed operation={} provider={} reason={}", operation, exception.provider(), exception.getMessage());
            return defaultValue;
        }
    }

    private List<SearchResult> toSearchResults(List<ProviderSearchResult> results) {
        return (results == null ? List.<ProviderSearchResult>of() : results).stream()
                .map(item -> new SearchResult(item.symbol(), item.name(), item.exchange(), item.currency(), item.type()))
                .toList();
    }

    private List<ProviderSearchResult> mergeSearchResults(List<ProviderSearchResult> providerResults,
                                                           List<ProviderSearchResult> canonicalResults) {
        Map<String, ProviderSearchResult> bySymbol = new LinkedHashMap<>();
        addSearchResults(bySymbol, providerResults);
        addSearchResults(bySymbol, canonicalResults);
        return new ArrayList<>(bySymbol.values());
    }

    private void addSearchResults(Map<String, ProviderSearchResult> target, List<ProviderSearchResult> results) {
        if (results == null) return;
        for (ProviderSearchResult item : results) {
            if (item == null || item.type() != InstrumentType.ETF || item.symbol() == null || item.symbol().isBlank()) continue;
            target.putIfAbsent(item.symbol().trim().toUpperCase(Locale.ROOT), item);
        }
    }

    private boolean containsExactEtf(List<ProviderSearchResult> results, String symbol) {
        if (results == null) return false;
        return results.stream().anyMatch(item -> item != null && item.type() == InstrumentType.ETF
                && symbol.equalsIgnoreCase(item.symbol()));
    }

    private <T> ProviderCall<T> callWithProvider(Function<MarketDataProvider, T> operation) {
        ProviderSelection selection = providerSelection();
        List<ProviderId> order = new ArrayList<>();
        order.add(selection.primary());
        if (selection.fallback() != null && selection.fallback() != selection.primary()) order.add(selection.fallback());
        ProviderException last = null;
        T emptyValue = null;
        ProviderId emptyProvider = null;
        for (ProviderId providerId : order) {
            MarketDataProvider provider = providers.get(providerId);
            if (provider == null || !provider.isConfigured()) continue;
            for (int attempt = 1; attempt <= providerAttempts; attempt++) {
                try {
                    T value = operation.apply(provider);
                    if (value == null) {
                        throw new ProviderException(providerId, "Provider returned an empty response", false);
                    }
                    if (value instanceof List<?> list && list.isEmpty()) {
                        emptyValue = value;
                        emptyProvider = providerId;
                        continue;
                    }
                    if (value instanceof Optional<?> optional && optional.isEmpty()) {
                        emptyValue = value;
                        emptyProvider = providerId;
                        continue;
                    }
                    return new ProviderCall<>(value, providerId);
                } catch (ProviderException exception) {
                    last = exception;
                    if (!exception.retryable() || attempt == providerAttempts) break;
                    log.debug("retrying market provider={} attempt={} reason={}", providerId, attempt + 1, exception.getMessage());
                }
            }
        }
        if (emptyProvider != null) return new ProviderCall<>(emptyValue, emptyProvider);
        throw last == null ? new ProviderException(selection.primary(), "No configured market data provider", false) : last;
    }

    private ProviderSelection providerSelection() {
        Map<String, String> values = settingRepository.findAll().stream()
                .collect(Collectors.toMap(AppSettingEntity::getKey, setting -> setting.getValue() == null ? "" : setting.getValue(),
                        (first, ignored) -> first));
        return new ProviderSelection(parseProvider(values.get("primaryProvider"), configuredPrimary, false),
                parseProvider(values.get("fallbackProvider"), configuredFallback, true));
    }

    private ZoneId currentZone() {
        Optional<AppSettingEntity> setting = settingRepository.findById("timezone");
        String value = setting == null ? null : setting.map(AppSettingEntity::getValue).orElse(null);
        if (value == null || value.isBlank()) return zone;
        try {
            return ZoneId.of(value.trim());
        } catch (RuntimeException exception) {
            log.warn("ignoring invalid persisted timezone value={}", value);
            return zone;
        }
    }

    private List<PriceDailyEntity> canonicalPrices(List<PriceDailyEntity> rows) {
        List<ProviderId> priority = providerPriority();
        Map<LocalDate, PriceDailyEntity> selected = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(PriceDailyEntity::getTradeDate)
                        .thenComparing(entity -> ProviderPriority.rank(entity.getSource(), priority))
                        .thenComparing(PriceDailyEntity::getCreatedAt, Comparator.nullsLast(java.time.Instant::compareTo))
                        .thenComparing(entity -> entity.getId() == null ? "" : entity.getId().toString()))
                .forEach(entity -> selected.putIfAbsent(entity.getTradeDate(), entity));
        return selected.values().stream().sorted(Comparator.comparing(PriceDailyEntity::getTradeDate)).toList();
    }

    private record ProviderSelection(ProviderId primary, ProviderId fallback) { }

    private record ProviderCall<T>(T value, ProviderId provider) { }
}
