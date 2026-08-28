package com.dca.terminal.observability;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.instrument.InstrumentType;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataProvider;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.PriceDailyRepository;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;
import com.dca.terminal.marketdata.ProviderModels.SplitEvent;
import com.dca.terminal.marketdata.QuoteLatestRepository;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.AssetRepository;
import com.dca.terminal.plan.PlanRepository;
import com.dca.terminal.plan.PlanService;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.portfolio.PortfolioSnapshotRepository;
import com.dca.terminal.settings.AppSettingRepository;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionService;
import com.dca.terminal.transaction.TransactionType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityMetricsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
    private static final Set<String> FORBIDDEN = Set.of(
            "symbol", "ticker", "notes", "password", "credentials", "sql", "key", "apikey", "api_key",
            "secret", "token", "authorization");

    @Test
    void rejectsSymbolAndSensitiveMetricTagNames() {
        assertThrows(IllegalArgumentException.class, () -> ObservabilityMetrics.tags("symbol", "VOO"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityMetrics.tags("notes", "buy more"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityMetrics.tags("password", "secret"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityMetrics.tags("sql", "select * from investment_transaction"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityMetrics.tags("provider", "YAHOO", "api_key", "abcd"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityMetrics.tags("provider", "password=hunter2"));
    }

    @Test
    void recordsLowCardinalityTagsWithoutSymbolOrSecrets() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.search("QQQ")).thenReturn(List.of(
                new ProviderSearchResult("QQQ", "Invesco QQQ Trust", "NASDAQ", "USD", InstrumentType.ETF)));
        InstrumentEntity instrument = instrument("VOO");
        when(yahoo.getHistoricalPrices(any(), any(), any())).thenReturn(List.of(
                new PriceBar(LocalDate.of(2026, 8, 27), bd("100"), bd("101"), bd("99"), bd("100"), bd("100"), 1L)));
        when(yahoo.getSplits(any(), any(), any())).thenReturn(List.of(
                new SplitEvent(LocalDate.of(2026, 8, 1), bd("2"), bd("1"))));
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findTopByInstrumentIdOrderByTradeDateDesc(instrument.getId())).thenReturn(Optional.empty());
        when(prices.findByInstrumentIdAndTradeDateAndSource(any(), any(), anyString())).thenReturn(Optional.empty());
        when(prices.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findByInstrumentIdAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(splits.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.save(any(InstrumentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketDataService marketData = new MarketDataService(instruments, prices, mock(QuoteLatestRepository.class),
                splits, mock(FundNavDailyRepository.class), settings(), List.of(yahoo), CLOCK, ZoneOffset.UTC,
                "YAHOO", "TWELVE_DATA", 60, 1, mock(PortfolioSnapshotInvalidator.class), registry);

        marketData.search("QQQ");
        marketData.sync(instrument);

        Timer search = registry.find(ObservabilityMetrics.PROVIDER_REQUEST)
                .tag("provider", "YAHOO").tag("operation", "search").tag("outcome", "success").timer();
        Timer history = registry.find(ObservabilityMetrics.PROVIDER_REQUEST)
                .tag("provider", "YAHOO").tag("operation", "history").tag("outcome", "success").timer();
        assertEquals(1, search.count());
        assertEquals(1, history.count());
        assertEquals(1, registry.find(ObservabilityMetrics.MARKET_SYNC_ROWS).tag("status", "FRESH").counter().count(), 0.0);
        assertEquals(1, registry.find(ObservabilityMetrics.MARKET_SYNC_SPLITS).tag("status", "FRESH").counter().count(), 0.0);
        assertNoForbiddenTags(registry);
    }

    @Test
    void recordsReplayInvalidateRebuildAndCsvCountsWithoutHighCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = instrument("VOO");
        when(instrument.getId()).thenReturn(instrumentId);
        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(LocalDate.of(2026, 8, 1));
        buy.setQuantity(new BigDecimal("1"));
        buy.setUnitPrice(new BigDecimal("100"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(1L);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(any(LocalDate.class)))
                .thenReturn(List.of(buy));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of(buy));
        when(transactions.existsByImportFingerprint(anyString())).thenReturn(false);
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findAllByInstrumentIdIn(anyCollection())).thenReturn(List.of());
        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        when(snapshots.findBySnapshotDate(any())).thenReturn(Optional.empty());
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService portfolio = new PortfolioService(transactions, mock(InstrumentRepository.class), prices, quotes,
                splits, snapshots, mock(PlanRepository.class), mock(AssetRepository.class), marketData, CLOCK,
                ZoneOffset.UTC, registry);
        portfolio.currentViews();
        assertEquals(1, registry.find(ObservabilityMetrics.PORTFOLIO_REPLAY).tag("mode", "current").timer().count());
        portfolio.rebuildTodaySnapshot();

        PortfolioSnapshotInvalidator invalidator = new PortfolioSnapshotInvalidator(snapshots, registry);
        invalidator.invalidateFrom(LocalDate.of(2026, 8, 1));
        invalidator.invalidateAll();

        InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
        when(instrumentRepository.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        TransactionService transactionService = new TransactionService(transactions, instrumentRepository,
                mock(SplitEventRepository.class), marketData, mock(PlanService.class), mock(PortfolioService.class),
                invalidator, CLOCK, ZoneOffset.UTC, registry);
        transactionService.preview(new MockMultipartFile("file", "transactions.csv", "text/csv",
                "date,type,symbol,quantity,price,fee\n2026-08-01,BUY,VOO,1,100,0\n2026-08-01,BUY,VOO,1.0,100.00,0\n".getBytes()));

        assertEquals(2, registry.find(ObservabilityMetrics.PORTFOLIO_REPLAY).tag("mode", "current").timer().count());
        assertEquals(2, registry.find(ObservabilityMetrics.PORTFOLIO_REPLAY_TRANSACTIONS).tag("mode", "current")
                .summary().count());
        assertEquals(1, registry.find(ObservabilityMetrics.SNAPSHOT_REBUILD).timer().count());
        assertEquals(1, registry.find(ObservabilityMetrics.SNAPSHOT_INVALIDATE).tag("mode", "from").timer().count());
        assertEquals(1, registry.find(ObservabilityMetrics.SNAPSHOT_INVALIDATE).tag("mode", "all").timer().count());
        assertEquals(2, registry.find(ObservabilityMetrics.CSV_ROWS).counter().count(), 0.0);
        assertEquals(1, registry.find(ObservabilityMetrics.CSV_INVALID).counter().count(), 0.0);
        assertEquals(1, registry.find(ObservabilityMetrics.CSV_DUPLICATE).counter().count(), 0.0);
        assertNoForbiddenTags(registry);
    }

    private static void assertNoForbiddenTags(SimpleMeterRegistry registry) {
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey();
                assertTrue(ObservabilityMetrics.ALLOWED_TAG_KEYS.contains(key),
                        () -> "unapproved metric tag " + key + " on " + meter.getId().getName());
                assertTrue(!FORBIDDEN.contains(key.toLowerCase(Locale.ROOT)),
                        () -> "forbidden metric tag " + key + " on " + meter.getId().getName());
                ObservabilityMetrics.validate(meter.getId().getTags());
            }
        }
    }

    private static AppSettingRepository settings() {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        return repository;
    }

    private static MarketDataProvider provider(ProviderId id) {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.id()).thenReturn(id);
        when(provider.isConfigured()).thenReturn(true);
        return provider;
    }

    private static InstrumentEntity instrument(String symbol) {
        InstrumentEntity entity = mock(InstrumentEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getSymbol()).thenReturn(symbol);
        when(entity.getName()).thenReturn(symbol + " ETF");
        when(entity.getCurrency()).thenReturn("USD");
        return entity;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
