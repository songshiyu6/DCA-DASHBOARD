package com.dca.terminal.marketdata;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.PriceDailyEntity;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.PriceDailyRepository;
import com.dca.terminal.marketdata.QuoteLatestRepository;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;
import com.dca.terminal.marketdata.ProviderModels.SplitEvent;
import com.dca.terminal.settings.AppSettingEntity;
import com.dca.terminal.settings.AppSettingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.dca.terminal.instrument.InstrumentDtos.SearchResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataServiceCorrectnessTest {
    private static final Instant NOW = Instant.parse("2026-08-27T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void requiresAnExactProviderConfirmedEtfBeforeCreatingAnInstrument() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("ZZZ")).thenReturn(Optional.empty());
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("ZZZ")).thenReturn(List.of(
                new ProviderSearchResult("VTI", "Vanguard Total Stock Market ETF", "ARCX", "USD", com.dca.terminal.instrument.InstrumentType.ETF)));

        MarketDataService service = service(instruments, yahoo);

        DomainException exception = assertThrows(DomainException.class, () -> service.add("ZZZ"));

        assertEquals("ETF_NOT_CONFIRMED", exception.code());
        verify(instruments, never()).save(any(InstrumentEntity.class));
    }

    @Test
    void mapsProviderDirectoryResultsForQqqAndVoo() {
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("QQQ")).thenReturn(List.of(
                new ProviderSearchResult("QQQ", "Invesco QQQ Trust", "NASDAQ", "USD",
                        com.dca.terminal.instrument.InstrumentType.ETF),
                new ProviderSearchResult("VOO", "Vanguard S&P 500 ETF", "NYSEArca", "USD",
                        com.dca.terminal.instrument.InstrumentType.ETF)));

        List<SearchResult> results = service(mock(InstrumentRepository.class), yahoo).search(" QQQ ");

        assertEquals(List.of("QQQ", "VOO"), results.stream().map(SearchResult::symbol).toList());
        assertEquals("Invesco QQQ Trust", results.getFirst().name());
        verify(yahoo).search("QQQ");
    }

    @Test
    void usesCanonicalCatalogForKnownEtfWhenDirectoryProviderIsUnavailable() {
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("QQQ")).thenThrow(new ProviderException(ProviderId.YAHOO, "HTTP 429", true));

        List<SearchResult> results = service(mock(InstrumentRepository.class), yahoo).search(" qqq ");

        assertEquals(1, results.size());
        assertEquals("QQQ", results.getFirst().symbol());
        assertEquals("Invesco QQQ Trust", results.getFirst().name());
        assertEquals("NASDAQ", results.getFirst().exchange());
        verify(yahoo).search("QQQ");
    }

    @Test
    void doesNotHideProviderFailureAsEmptyResultsForUnknownTicker() {
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("XYZ")).thenThrow(new ProviderException(ProviderId.YAHOO, "HTTP 429", true));

        DomainException exception = assertThrows(DomainException.class,
                () -> service(mock(InstrumentRepository.class), yahoo).search("XYZ"));

        assertEquals("MARKET_DATA_UNAVAILABLE", exception.code());
    }

    @Test
    void keepsAValidEmptyProviderResponseAsNoResults() {
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("XYZ")).thenReturn(List.of());

        assertEquals(List.of(), service(mock(InstrumentRepository.class), yahoo).search("XYZ"));
    }

    @Test
    void turnsNullProviderQuoteIntoUnavailableDataInsteadOfAnInternalError() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");
        when(instrument.getCurrency()).thenReturn("USD");
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.getLatestQuote(instrument)).thenReturn(null);
        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findById(instrumentId)).thenReturn(Optional.empty());
        when(quotes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FundNavDailyRepository nav = mock(FundNavDailyRepository.class);
        when(nav.findTopByInstrumentIdOrderByNavDateDesc(instrumentId)).thenReturn(Optional.empty());

        MarketDataService marketData = new MarketDataService(mock(InstrumentRepository.class),
                mock(PriceDailyRepository.class), quotes, mock(SplitEventRepository.class), nav, settings(),
                List.of(yahoo), CLOCK, ZoneOffset.UTC, "YAHOO", "TWELVE_DATA", 60, 1);

        var response = marketData.latestQuote(instrument);

        assertEquals(FreshnessStatus.UNAVAILABLE, response.status());
        assertEquals(null, response.price());
    }

    @Test
    void performsInitialFiveYearSyncAndExposesIncompleteStateWhenNoBarsArrive() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.empty());
        when(instruments.save(any(InstrumentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findTopByInstrumentIdOrderByTradeDateDesc(any())).thenReturn(Optional.empty());
        when(prices.findByInstrumentIdAndTradeDateAndSource(any(), any(), any())).thenReturn(Optional.empty());
        when(prices.save(any(PriceDailyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("VOO")).thenReturn(List.of(
                new ProviderSearchResult("VOO", "Vanguard S&P 500 ETF", "ARCX", "USD", com.dca.terminal.instrument.InstrumentType.ETF)));
        when(yahoo.getProfile(any())).thenReturn(Optional.empty());
        when(yahoo.getHistoricalPrices(any(), eq(LocalDate.of(2021, 8, 27)), eq(LocalDate.of(2026, 8, 27))))
                .thenReturn(List.of());
        when(yahoo.getSplits(any(), any(), any())).thenReturn(List.of());

        MarketDataService service = service(instruments, prices, yahoo);
        InstrumentEntity saved = service.add("VOO");

        assertEquals(FreshnessStatus.INSUFFICIENT_HISTORY, saved.getDataStatus());
        verify(yahoo).getHistoricalPrices(any(), eq(LocalDate.of(2021, 8, 27)), eq(LocalDate.of(2026, 8, 27)));
        verify(prices, never()).save(any(PriceDailyEntity.class));
    }

    @Test
    void storesInitialBarsAndMarksTheirInstrumentFresh() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.empty());
        when(instruments.save(any(InstrumentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findTopByInstrumentIdOrderByTradeDateDesc(any())).thenReturn(Optional.empty());
        when(prices.findByInstrumentIdAndTradeDateAndSource(any(), any(), any())).thenReturn(Optional.empty());
        when(prices.save(any(PriceDailyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("VOO")).thenReturn(List.of(
                new ProviderSearchResult("VOO", "Vanguard S&P 500 ETF", "ARCX", "USD", com.dca.terminal.instrument.InstrumentType.ETF)));
        when(yahoo.getProfile(any())).thenReturn(Optional.empty());
        when(yahoo.getHistoricalPrices(any(), any(), any())).thenReturn(List.of(
                new PriceBar(LocalDate.of(2026, 8, 27), bd("500"), bd("510"), bd("490"), bd("505"), null, 100L)));
        when(yahoo.getSplits(any(), any(), any())).thenReturn(List.of());

        InstrumentEntity saved = service(instruments, prices, yahoo).add("VOO");

        assertEquals(FreshnessStatus.FRESH, saved.getDataStatus());
        verify(prices).save(any(PriceDailyEntity.class));
    }

    @Test
    void readdingAnIncompleteInstrumentRetriesItsHistorySync() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        InstrumentEntity existing = new InstrumentEntity();
        existing.setSymbol("VOO");
        existing.setName("Vanguard S&P 500 ETF");
        existing.setDataStatus(FreshnessStatus.UNAVAILABLE);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(existing));
        when(instruments.save(any(InstrumentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findTopByInstrumentIdOrderByTradeDateDesc(any())).thenReturn(Optional.empty());
        when(prices.findByInstrumentIdAndTradeDateAndSource(any(), any(), any())).thenReturn(Optional.empty());
        when(prices.save(any(PriceDailyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.getHistoricalPrices(any(), any(), any())).thenReturn(List.of(
                new PriceBar(LocalDate.of(2026, 8, 27), bd("500"), bd("510"), bd("490"), bd("505"), bd("505"), 100L)));
        when(yahoo.getSplits(any(), any(), any())).thenReturn(List.of());

        InstrumentEntity result = service(instruments, prices, yahoo).add("VOO");

        assertEquals(FreshnessStatus.FRESH, result.getDataStatus());
        verify(yahoo).getHistoricalPrices(any(), eq(LocalDate.of(2021, 8, 27)), eq(LocalDate.of(2026, 8, 27)));
        verify(prices).save(any(PriceDailyEntity.class));
    }

    @Test
    void readsOneCanonicalPricePerDateUsingConfiguredProviderPriority() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        PriceDailyEntity fallback = price(instrument, LocalDate.of(2026, 8, 26), "90", "TWELVE_DATA");
        PriceDailyEntity primary = price(instrument, LocalDate.of(2026, 8, 26), "100", "YAHOO");
        PriceDailyEntity today = price(instrument, LocalDate.of(2026, 8, 27), "101", "YAHOO");
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdAndTradeDateBetweenOrderByTradeDateAsc(eq(instrumentId), any(), any()))
                .thenReturn(List.of(fallback, primary, today));

        var response = service(mock(InstrumentRepository.class), prices, provider(ProviderId.YAHOO))
                .prices(instrument, "1Y");

        assertEquals(FreshnessStatus.FRESH, response.status());
        assertEquals(List.of(bd("100"), bd("101")), response.data().stream().map(MarketDataDtos.PricePoint::close).toList());
    }

    @Test
    void marksEmptyDailyHistoryAsInsufficientWithAnActionableMessage() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdAndTradeDateBetweenOrderByTradeDateAsc(eq(instrumentId), any(), any()))
                .thenReturn(List.of());

        var response = service(mock(InstrumentRepository.class), prices, provider(ProviderId.YAHOO))
                .prices(instrument, "5Y");

        assertEquals(FreshnessStatus.INSUFFICIENT_HISTORY, response.status());
        assertEquals("Historical data is unavailable; retry the sync when the provider recovers", response.message());
    }

    @Test
    void keepsPrimaryCorporateActionWhenFallbackSyncArrivesLater() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        PriceDailyEntity latest = price(instrument, LocalDate.of(2026, 8, 26), "100", "YAHOO");
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findTopByInstrumentIdOrderByTradeDateDesc(instrumentId)).thenReturn(Optional.of(latest));
        when(prices.findByInstrumentIdAndTradeDateAndSource(any(), any(), any())).thenReturn(Optional.empty());
        when(prices.save(any(PriceDailyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SplitEventRepository splits = mock(SplitEventRepository.class);
        SplitEventEntity existing = mock(SplitEventEntity.class);
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(existing.getSource()).thenReturn("YAHOO");
        when(splits.findByInstrumentIdAndEffectiveDate(eq(instrumentId), eq(LocalDate.of(2026, 8, 27))))
                .thenReturn(Optional.of(existing));
        MarketDataProvider fallback = provider(ProviderId.TWELVE_DATA);
        when(fallback.getHistoricalPrices(any(), any(), any())).thenReturn(List.of(
                new PriceBar(LocalDate.of(2026, 8, 27), bd("100"), bd("101"), bd("99"), bd("100"), bd("100"), 1L)));
        when(fallback.getSplits(any(), any(), any())).thenReturn(List.of(
                new SplitEvent(LocalDate.of(2026, 8, 27), bd("2"), bd("1"))));
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.save(any(InstrumentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketDataService service = new MarketDataService(instruments, prices, mock(QuoteLatestRepository.class),
                splits, mock(FundNavDailyRepository.class), settings(), List.of(fallback), CLOCK,
                ZoneOffset.UTC, "YAHOO", "TWELVE_DATA", 60, 1);
        service.sync(instrument);

        verify(splits, never()).save(any(SplitEventEntity.class));
        assertEquals("YAHOO", existing.getSource());
    }

    @Test
    void noneDisablesConfiguredFallbackInRuntimeSettings() {
        AppSettingRepository settings = settings(new AppSettingEntity("fallbackProvider", "NONE"));
        MarketDataService service = new MarketDataService(mock(InstrumentRepository.class), mock(PriceDailyRepository.class),
                mock(QuoteLatestRepository.class), mock(SplitEventRepository.class), mock(FundNavDailyRepository.class),
                settings, List.of(provider(ProviderId.YAHOO), provider(ProviderId.TWELVE_DATA)), CLOCK, ZoneOffset.UTC,
                "YAHOO", "TWELVE_DATA", 60, 1);

        assertEquals("NONE", service.fallbackProvider());
        assertEquals(ProviderId.YAHOO, service.providerPriority().getFirst());
    }

    @Test
    void treatsTheLastFridayCloseAsFreshOnTheFollowingWeekend() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdAndTradeDateBetweenOrderByTradeDateAsc(
                eq(instrumentId), any(), any())).thenReturn(List.of(price(instrument,
                LocalDate.of(2026, 8, 28), "500", "YAHOO")));

        MarketDataService service = service(mock(InstrumentRepository.class), prices, provider(ProviderId.YAHOO),
                Clock.fixed(Instant.parse("2026-08-29T16:00:00Z"), ZoneOffset.UTC));

        var response = service.prices(instrument, "1W");

        assertEquals(FreshnessStatus.FRESH, response.status());
        assertEquals(LocalDate.of(2026, 8, 28), response.asOf());
    }

    @Test
    void treatsTheLastPreHolidayCloseAsFreshOnAnObservedMarketHoliday() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdAndTradeDateBetweenOrderByTradeDateAsc(
                eq(instrumentId), any(), any())).thenReturn(List.of(price(instrument,
                LocalDate.of(2026, 7, 2), "500", "YAHOO")));

        MarketDataService service = service(mock(InstrumentRepository.class), prices, provider(ProviderId.YAHOO),
                Clock.fixed(Instant.parse("2026-07-03T20:00:00Z"), ZoneOffset.UTC));

        var response = service.prices(instrument, "1W");

        assertEquals(FreshnessStatus.FRESH, response.status());
        assertEquals(LocalDate.of(2026, 7, 2), response.asOf());
    }

    private static MarketDataService service(InstrumentRepository instruments, MarketDataProvider provider) {
        return service(instruments, mock(PriceDailyRepository.class), provider, CLOCK);
    }

    private static MarketDataService service(InstrumentRepository instruments, PriceDailyRepository prices,
                                             MarketDataProvider provider) {
        return service(instruments, prices, provider, CLOCK);
    }

    private static MarketDataService service(InstrumentRepository instruments, PriceDailyRepository prices,
                                             MarketDataProvider provider, Clock clock) {
        return new MarketDataService(instruments, prices, mock(QuoteLatestRepository.class),
                mock(SplitEventRepository.class), mock(FundNavDailyRepository.class), settings(), List.of(provider),
                clock, ZoneOffset.UTC, "YAHOO", "TWELVE_DATA", 60, 1);
    }

    private static AppSettingRepository settings(AppSettingEntity... values) {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(values));
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        return repository;
    }

    private static MarketDataProvider provider(ProviderId id) {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.id()).thenReturn(id);
        when(provider.isConfigured()).thenReturn(true);
        return provider;
    }

    private static PriceDailyEntity price(InstrumentEntity instrument, LocalDate date, String close, String source) {
        PriceDailyEntity price = new PriceDailyEntity();
        price.setInstrument(instrument);
        price.setTradeDate(date);
        price.setClose(bd(close));
        price.setAdjustedClose(bd(close));
        price.setSource(source);
        return price;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
