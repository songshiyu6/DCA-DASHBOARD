package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.PriceDailyEntity;
import com.dca.terminal.marketdata.MarketDataEntities.QuoteLatestEntity;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.PriceDailyRepository;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.marketdata.QuoteLatestRepository;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.AssetRepository;
import com.dca.terminal.plan.InvestmentPlanAssetEntity;
import com.dca.terminal.plan.InvestmentPlanEntity;
import com.dca.terminal.plan.PlanRepository;
import com.dca.terminal.plan.PlanStatus;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioDashboardProjectionEquivalenceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void sharedCurrentProjectionMatchesIndependentSummaryHoldingsAndAllocationFieldByField() {
        Fixture fixture = fixture();
        PortfolioService service = fixture.service;

        PortfolioDtos.SummaryResponse independentSummary = service.summary();
        List<PortfolioDtos.HoldingResponse> independentHoldings = service.holdings();
        List<PortfolioDtos.AllocationResponse> independentAllocation = service.allocation();

        verify(fixture.transactions, times(3))
                .findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(any(LocalDate.class));
        verify(fixture.splits, times(3))
                .findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                        anyCollection(), any(LocalDate.class));
        verify(fixture.prices, times(3))
                .findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                        anyCollection(), any(LocalDate.class));
        verify(fixture.quotes, times(3)).findAllByInstrumentIdIn(anyCollection());
        verify(fixture.transactions, never()).findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
        verify(fixture.snapshots, never()).findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any());

        clearInvocations(fixture.transactions, fixture.splits, fixture.prices, fixture.quotes, fixture.snapshots);

        PortfolioService.CurrentViews shared = service.currentViews();

        verify(fixture.transactions, times(1))
                .findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(any(LocalDate.class));
        verify(fixture.splits, times(1))
                .findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                        anyCollection(), any(LocalDate.class));
        verify(fixture.prices, times(1))
                .findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                        anyCollection(), any(LocalDate.class));
        verify(fixture.quotes, times(1)).findAllByInstrumentIdIn(anyCollection());
        verify(fixture.transactions, never()).findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
        verify(fixture.snapshots, never()).findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any());

        assertSummaryEqual(independentSummary, shared.summary());
        assertHoldingsEqual(independentHoldings, shared.holdings());
        assertAllocationsEqual(independentAllocation, shared.allocation());

        List<PortfolioDtos.HistoryPoint> history = service.history("1Y");
        verify(fixture.transactions).findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
        assertNotNull(history);
        assertEquals(LocalDate.of(2026, 1, 15), history.getFirst().date());
        assertEquals(LocalDate.of(2026, 8, 27), history.getLast().date());
    }

    private static Fixture fixture() {
        UUID vooId = UUID.randomUUID();
        UUID qqqId = UUID.randomUUID();
        InstrumentEntity voo = instrument(vooId, "VOO", "Vanguard S&P 500 ETF");
        InstrumentEntity qqq = instrument(qqqId, "QQQ", "Invesco QQQ Trust");

        TransactionEntity buyVoo = transaction(voo, TransactionType.BUY, "2026-01-15", "10", "100", "1", 1L);
        TransactionEntity buyQqq = transaction(qqq, TransactionType.BUY, "2026-03-01", "5", "200", "0", 2L);
        TransactionEntity sellVoo = transaction(voo, TransactionType.SELL, "2026-04-01", "5", "120", "1", 3L);
        TransactionEntity dividend = transaction(voo, TransactionType.DIVIDEND, "2026-05-01", null, null, null, 4L);
        dividend.setAmount(new BigDecimal("10"));
        TransactionEntity fee = transaction(voo, TransactionType.FEE, "2026-06-01", null, null, null, 5L);
        fee.setAmount(new BigDecimal("2"));
        List<TransactionEntity> transactions = List.of(buyVoo, buyQqq, sellVoo, dividend, fee);

        SplitEventEntity split = new SplitEventEntity();
        split.setInstrument(voo);
        split.setEffectiveDate(LocalDate.of(2026, 2, 1));
        split.setNumerator(new BigDecimal("2"));
        split.setDenominator(BigDecimal.ONE);
        split.setSource("YAHOO");

        QuoteLatestEntity vooQuote = new QuoteLatestEntity();
        vooQuote.setInstrumentId(vooId);
        vooQuote.setPrice(new BigDecimal("130"));
        vooQuote.setChangePercent(new BigDecimal("0.01"));
        vooQuote.setStatus(FreshnessStatus.FRESH);

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(any(LocalDate.class)))
                .thenReturn(transactions);
        when(transactionRepository.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(transactions);

        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(split));

        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(
                dailyPrice(voo, "2026-01-15", "100"),
                dailyPrice(voo, "2026-04-01", "120"),
                dailyPrice(voo, "2026-08-27", "128"),
                dailyPrice(qqq, "2026-03-01", "200"),
                dailyPrice(qqq, "2026-08-27", "210")));

        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findAllByInstrumentIdIn(anyCollection())).thenReturn(List.of(vooQuote));

        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        when(snapshots.findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(any(), any())).thenReturn(List.of());

        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        UUID planId = UUID.randomUUID();
        when(plan.getId()).thenReturn(planId);
        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findFirstByStatus(PlanStatus.ACTIVE)).thenReturn(Optional.of(plan));

        InvestmentPlanAssetEntity vooAsset = planAsset(voo, "0.60");
        InvestmentPlanAssetEntity qqqAsset = planAsset(qqq, "0.40");
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(vooAsset, qqqAsset));

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactionRepository, mock(InstrumentRepository.class),
                prices, quotes, splits, snapshots, plans, assets, marketData, CLOCK, ZoneOffset.UTC);
        return new Fixture(service, transactionRepository, splits, prices, quotes, snapshots);
    }

    private static InstrumentEntity instrument(UUID id, String symbol, String name) {
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(id);
        when(instrument.getSymbol()).thenReturn(symbol);
        when(instrument.getName()).thenReturn(name);
        return instrument;
    }

    private static TransactionEntity transaction(InstrumentEntity instrument, TransactionType type, String date,
                                                 String quantity, String price, String fee, long ledgerOrder) {
        TransactionEntity entity = new TransactionEntity();
        entity.setInstrument(instrument);
        entity.setTransactionType(type);
        entity.setTradeDate(LocalDate.parse(date));
        entity.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        entity.setUnitPrice(price == null ? null : new BigDecimal(price));
        entity.setFee(fee == null ? BigDecimal.ZERO : new BigDecimal(fee));
        entity.setLedgerOrder(ledgerOrder);
        return entity;
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

    private static InvestmentPlanAssetEntity planAsset(InstrumentEntity instrument, String weight) {
        InvestmentPlanAssetEntity asset = mock(InvestmentPlanAssetEntity.class);
        when(asset.getId()).thenReturn(UUID.randomUUID());
        when(asset.getInstrument()).thenReturn(instrument);
        when(asset.getTargetWeight()).thenReturn(new BigDecimal(weight));
        return asset;
    }

    private static void assertSummaryEqual(PortfolioDtos.SummaryResponse expected, PortfolioDtos.SummaryResponse actual) {
        assertDecimal(expected.marketValue(), actual.marketValue());
        assertDecimal(expected.costBasis(), actual.costBasis());
        assertDecimal(expected.netInvested(), actual.netInvested());
        assertDecimal(expected.unrealizedPnl(), actual.unrealizedPnl());
        assertDecimal(expected.realizedPnl(), actual.realizedPnl());
        assertDecimal(expected.dividendIncome(), actual.dividendIncome());
        assertDecimal(expected.totalFees(), actual.totalFees());
        assertDecimal(expected.totalPnl(), actual.totalPnl());
        assertDecimal(expected.xirr(), actual.xirr());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.asOf(), actual.asOf());
    }

    private static void assertHoldingsEqual(List<PortfolioDtos.HoldingResponse> expected,
                                            List<PortfolioDtos.HoldingResponse> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            PortfolioDtos.HoldingResponse left = expected.get(index);
            PortfolioDtos.HoldingResponse right = actual.get(index);
            assertEquals(left.symbol(), right.symbol());
            assertEquals(left.name(), right.name());
            assertDecimal(left.price(), right.price());
            assertDecimal(left.todayPercent(), right.todayPercent());
            assertDecimal(left.shares(), right.shares());
            assertDecimal(left.avgCost(), right.avgCost());
            assertDecimal(left.costBasis(), right.costBasis());
            assertDecimal(left.marketValue(), right.marketValue());
            assertDecimal(left.unrealizedPnl(), right.unrealizedPnl());
            assertDecimal(left.returnPercent(), right.returnPercent());
            assertDecimal(left.allocation(), right.allocation());
            assertEquals(left.dataStatus(), right.dataStatus());
        }
    }

    private static void assertAllocationsEqual(List<PortfolioDtos.AllocationResponse> expected,
                                               List<PortfolioDtos.AllocationResponse> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            PortfolioDtos.AllocationResponse left = expected.get(index);
            PortfolioDtos.AllocationResponse right = actual.get(index);
            assertEquals(left.symbol(), right.symbol());
            assertDecimal(left.targetWeight(), right.targetWeight());
            assertDecimal(left.actualWeight(), right.actualWeight());
            assertDecimal(left.drift(), right.drift());
            assertDecimal(left.marketValue(), right.marketValue());
        }
    }

    private static void assertDecimal(BigDecimal expected, BigDecimal actual) {
        if (expected == null || actual == null) {
            assertNull(expected);
            assertNull(actual);
            return;
        }
        assertEquals(0, expected.compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    private record Fixture(PortfolioService service, TransactionRepository transactions, SplitEventRepository splits,
                           PriceDailyRepository prices, QuoteLatestRepository quotes,
                           PortfolioSnapshotRepository snapshots) { }
}
