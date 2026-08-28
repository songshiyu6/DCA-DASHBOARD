package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.PriceDailyEntity;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.PriceDailyRepository;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.marketdata.QuoteLatestRepository;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.AssetRepository;
import com.dca.terminal.plan.PlanRepository;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioServiceCorrectnessTest {

    @Test
    void doesNotReportMissingPriceAsAnUnrealizedLoss() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

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
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        PriceDailyEntity invalidDaily = new PriceDailyEntity();
        invalidDaily.setInstrument(instrument);
        invalidDaily.setTradeDate(LocalDate.of(2026, 8, 27));
        invalidDaily.setClose(BigDecimal.ZERO);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(invalidDaily));
        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findAllByInstrumentIdIn(anyCollection())).thenReturn(List.of());
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactions, mock(InstrumentRepository.class), prices, quotes,
                splits, mock(PortfolioSnapshotRepository.class), mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

        PortfolioDtos.SummaryResponse summary = service.summary();

        assertEquals(FreshnessStatus.PARTIAL, summary.status());
        assertEquals(0, summary.marketValue().signum());
        assertNull(summary.unrealizedPnl());
        assertNull(summary.totalPnl());
        assertNull(summary.xirr());

        PortfolioDtos.HoldingResponse holding = service.holdings().getFirst();
        assertNull(holding.price());
        assertNull(holding.marketValue());
        assertNull(holding.unrealizedPnl());
        assertNull(holding.returnPercent());
        assertNull(holding.allocation());
    }

    @Test
    void rebuildsFullHistoryWhenOnlyTodaySnapshotExists() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

        LocalDate firstTradeDate = LocalDate.of(2026, 8, 1);
        LocalDate today = LocalDate.of(2026, 8, 27);
        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(firstTradeDate);
        buy.setQuantity(new BigDecimal("1"));
        buy.setUnitPrice(new BigDecimal("100"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(1L);

        PortfolioSnapshotEntity todaySnapshot = new PortfolioSnapshotEntity();
        todaySnapshot.setSnapshotDate(today);
        todaySnapshot.setMarketValue(new BigDecimal("210"));
        todaySnapshot.setCostBasis(new BigDecimal("100"));
        todaySnapshot.setNetCashFlow(new BigDecimal("100"));
        todaySnapshot.setUnrealizedPnl(new BigDecimal("110"));
        todaySnapshot.setDataStatus(FreshnessStatus.FRESH);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of(buy));
        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        when(snapshots.findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any()))
                .thenReturn(List.of(todaySnapshot));
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(
                dailyPrice(instrument, "2026-08-01", "100"),
                dailyPrice(instrument, "2026-08-27", "210")));
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactions, mock(InstrumentRepository.class), prices,
                mock(QuoteLatestRepository.class), splits, snapshots, mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

        List<PortfolioDtos.HistoryPoint> history = service.history("1M");

        assertEquals(27, history.size());
        assertEquals(firstTradeDate, history.getFirst().date());
        assertEquals(today, history.getLast().date());
        assertEquals(history.size(), history.stream().map(point -> point.date()).distinct().count());

        PortfolioSnapshotRepository emptySnapshots = mock(PortfolioSnapshotRepository.class);
        when(emptySnapshots.findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any())).thenReturn(List.of());
        PortfolioService liveReplayService = new PortfolioService(transactions, mock(InstrumentRepository.class), prices,
                mock(QuoteLatestRepository.class), splits, emptySnapshots, mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);
        PortfolioDtos.HistoryPoint snapshotPoint = history.getLast();
        PortfolioDtos.HistoryPoint livePoint = liveReplayService.history("1M").getLast();
        assertSameDecimal(snapshotPoint.marketValue(), livePoint.marketValue());
        assertSameDecimal(snapshotPoint.costBasis(), livePoint.costBasis());
        assertSameDecimal(snapshotPoint.netInvested(), livePoint.netInvested());
        assertEquals(snapshotPoint.status(), livePoint.status());
    }

    @Test
    void mergesSnapshotsWithReplayForMissingDatesInSortedUniqueOrder() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(LocalDate.of(2026, 8, 1));
        buy.setQuantity(new BigDecimal("1"));
        buy.setUnitPrice(new BigDecimal("100"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(1L);

        PortfolioSnapshotEntity augustFirst = snapshot("2026-08-01", "100", "100", "100", "0", FreshnessStatus.FRESH);
        PortfolioSnapshotEntity augustThird = snapshot("2026-08-03", "110", "100", "100", "10", FreshnessStatus.FRESH);
        PortfolioSnapshotEntity duplicateAugustThird = snapshot("2026-08-03", "999", "999", "999", "999", FreshnessStatus.FRESH);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of(buy));
        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        when(snapshots.findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any()))
                .thenReturn(List.of(augustThird, duplicateAugustThird, augustFirst));
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(
                dailyPrice(instrument, "2026-08-01", "100"),
                dailyPrice(instrument, "2026-08-03", "110"),
                dailyPrice(instrument, "2026-08-27", "120")));
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactions, mock(InstrumentRepository.class), prices,
                mock(QuoteLatestRepository.class), splits, snapshots, mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

        List<PortfolioDtos.HistoryPoint> history = service.history("1M");

        assertEquals(27, history.size());
        assertEquals(history.size(), history.stream().map(PortfolioDtos.HistoryPoint::date).distinct().count());
        assertEquals(history.stream().map(PortfolioDtos.HistoryPoint::date).sorted().toList(),
                history.stream().map(PortfolioDtos.HistoryPoint::date).toList());
        assertDecimal("100", history.stream().filter(point -> point.date().equals(LocalDate.of(2026, 8, 2)))
                .findFirst().orElseThrow().marketValue());
        assertDecimal("110", history.stream().filter(point -> point.date().equals(LocalDate.of(2026, 8, 3)))
                .findFirst().orElseThrow().marketValue());
    }

    @Test
    void keepsMissingHistoricalPricePartialWithNullUnrealizedPnl() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(LocalDate.of(2026, 8, 1));
        buy.setQuantity(new BigDecimal("1"));
        buy.setUnitPrice(new BigDecimal("100"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(1L);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of(buy));
        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        when(snapshots.findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any())).thenReturn(List.of());
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(dailyPrice(instrument, "2026-08-27", "210")));
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactions, mock(InstrumentRepository.class), prices,
                mock(QuoteLatestRepository.class), splits, snapshots, mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

        PortfolioDtos.HistoryPoint first = service.history("1M").getFirst();

        assertEquals(FreshnessStatus.PARTIAL, first.status());
        assertNull(first.marketValue());
        assertDecimal("100", first.costBasis());
        assertNull(first.unrealizedPnl());
    }

    @Test
    void replaysHistoryIncrementallyWithoutUsingAStarterTransactionOrFuturePrice() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(LocalDate.of(2026, 8, 1));
        buy.setQuantity(new BigDecimal("1"));
        buy.setUnitPrice(new BigDecimal("100"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(1L);
        TransactionEntity laterBuy = new TransactionEntity();
        laterBuy.setInstrument(instrument);
        laterBuy.setTransactionType(TransactionType.BUY);
        laterBuy.setTradeDate(LocalDate.of(2026, 8, 20));
        laterBuy.setQuantity(new BigDecimal("1"));
        laterBuy.setUnitPrice(new BigDecimal("200"));
        laterBuy.setFee(BigDecimal.ZERO);
        laterBuy.setLedgerOrder(2L);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of(buy, laterBuy));
        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        when(snapshots.findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any())).thenReturn(List.of());
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
            anyCollection(), any(LocalDate.class))).thenReturn(List.of(
                dailyPrice(instrument, "2026-08-01", "100"),
                dailyPrice(instrument, "2026-08-10", "110"),
                dailyPrice(instrument, "2026-08-20", "200"),
                dailyPrice(instrument, "2026-08-27", "210")));
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactions, mock(InstrumentRepository.class), prices,
                mock(QuoteLatestRepository.class), splits, snapshots, mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

        List<PortfolioDtos.HistoryPoint> history = service.history("1M");

        PortfolioDtos.HistoryPoint beforeLaterPurchase = history.stream()
                .filter(point -> point.date().equals(LocalDate.of(2026, 8, 10))).findFirst().orElseThrow();
        PortfolioDtos.HistoryPoint laterPurchaseDate = history.stream()
                .filter(point -> point.date().equals(LocalDate.of(2026, 8, 20))).findFirst().orElseThrow();
        PortfolioDtos.HistoryPoint latest = history.getLast();
        assertDecimal("110", beforeLaterPurchase.marketValue());
        assertDecimal("100", beforeLaterPurchase.costBasis());
        assertDecimal("400", laterPurchaseDate.marketValue());
        assertDecimal("300", laterPurchaseDate.costBasis());
        assertDecimal("420", latest.marketValue());
        assertDecimal("300", latest.costBasis());
    }

    private static PriceDailyEntity dailyPrice(InstrumentEntity instrument, String date, String close) {
        PriceDailyEntity price = new PriceDailyEntity();
        price.setInstrument(instrument);
        price.setTradeDate(LocalDate.parse(date));
        price.setClose(new BigDecimal(close));
        price.setAdjustedClose(new BigDecimal(close));
        price.setSource("YAHOO");
        return price;
    }

    private static PortfolioSnapshotEntity snapshot(String date, String marketValue, String netCashFlow,
                                                    String costBasis, String unrealizedPnl, FreshnessStatus status) {
        PortfolioSnapshotEntity snapshot = new PortfolioSnapshotEntity();
        snapshot.setSnapshotDate(LocalDate.parse(date));
        snapshot.setMarketValue(new BigDecimal(marketValue));
        snapshot.setNetCashFlow(new BigDecimal(netCashFlow));
        snapshot.setCostBasis(new BigDecimal(costBasis));
        snapshot.setUnrealizedPnl(new BigDecimal(unrealizedPnl));
        snapshot.setDataStatus(status);
        return snapshot;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static void assertSameDecimal(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual));
    }
}
