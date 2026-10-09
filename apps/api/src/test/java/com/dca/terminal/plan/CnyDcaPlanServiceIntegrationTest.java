package com.dca.terminal.plan;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CnyDcaPlanServiceIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"),
            ZoneId.of("UTC"));
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    @Test
    void cycleDashboardProgressAndNextDcaIncludeCnyWithoutPersistingSyntheticUsdTransactions() {
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID qqqmId = UUID.randomUUID();
        InvestmentPlanEntity plan = plan(planId);
        InvestmentPlanCycleEntity cycle = cycle(cycleId, plan);
        InstrumentEntity qqqm = instrument("QQQM", qqqmId);
        InvestmentPlanCycleAssetEntity cycleAsset = mock(InvestmentPlanCycleAssetEntity.class);
        when(cycleAsset.getInstrument()).thenReturn(qqqm);
        when(cycleAsset.getTargetWeight()).thenReturn(BigDecimal.ONE);
        when(cycleAsset.getPlannedAmount()).thenReturn(bd("150"));
        InvestmentPlanAssetEntity asset = mock(InvestmentPlanAssetEntity.class);
        when(asset.getInstrument()).thenReturn(qqqm);
        when(asset.getTargetWeight()).thenReturn(BigDecimal.ONE);

        TransactionEntity realBuy = new TransactionEntity();
        realBuy.setInstrument(qqqm);
        realBuy.setTransactionType(TransactionType.BUY);
        realBuy.setTradeDate(LocalDate.of(2026, 8, 12));
        realBuy.setQuantity(BigDecimal.ONE);
        realBuy.setUnitPrice(bd("50"));
        realBuy.setFee(BigDecimal.ZERO);

        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findByPlanIdAndPeriod(org.mockito.ArgumentMatchers.eq(planId),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.of(cycle));
        when(cycles.findAllByPlanIdOrderByPeriodAsc(planId)).thenReturn(List.of(cycle));
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        when(cycleAssets.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of(cycleAsset));
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(asset));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId))
                .thenReturn(List.of(realBuy));
        PortfolioService portfolio = mock(PortfolioService.class);
        when(portfolio.currentMarketValues()).thenReturn(Map.of(qqqmId, bd("50")));
        when(portfolio.currentValuations(anyCollection())).thenReturn(List.of(
                new PortfolioService.CurrentValuation(qqqmId, bd("50"), bd("50"), FreshnessStatus.FRESH)));
        CnyFundPlanProjection projection = mock(CnyFundPlanProjection.class);
        when(projection.snapshot(any(LocalDate.class))).thenReturn(new CnyFundPlanProjection.Snapshot(
                Map.of(AUGUST, new CnyFundPlanProjection.Month(bd("40"), Map.of("QQQM", bd("40")), true)),
                Map.of("QQQM", bd("200")), Set.of(), Set.of()));

        PlanService service = new PlanService(plans, assets, cycles, cycleAssets,
                mock(InstrumentRepository.class), transactions, portfolio, projection, CLOCK, ZoneId.of("UTC"));

        PlanDtos.CycleResponse response = service.cycle(planId, "2026-08");
        assertThat(response.executedAmount()).isEqualByComparingTo("90");
        assertThat(response.cnyFundExecutedUsd()).isEqualByComparingTo("40");
        assertThat(response.assets().getFirst().executedAmount()).isEqualByComparingTo("90");
        assertThat(response.status()).isEqualTo(CycleStatus.PARTIAL);

        PlanDtos.ContributionProgress progress = service.contributionProgress(planId);
        assertThat(progress.executed()).isEqualByComparingTo("90");
        assertThat(progress.cnyFundExecutedUsd()).isEqualByComparingTo("40");
        assertThat(progress.executionRate()).isEqualByComparingTo("0.6");

        PlanDtos.NextDcaResponse next = service.nextDca(planId).orElseThrow();
        assertThat(next.amount()).isEqualByComparingTo("60");
        assertThat(next.status()).isEqualTo(FreshnessStatus.FRESH);
        PlanDtos.RecommendationResponse planPage = service.recommendation(planId, null);
        assertThat(planPage.amount()).isEqualByComparingTo("60");
        assertThat(planPage.items().getFirst().suggestedAmount()).isEqualByComparingTo("60");
        // Only the real USD BUY may be written to the old USD cycle rows.
        verify(cycle, atLeastOnce()).setExecutedAmount(bd("50"));
        verify(cycleAsset, atLeastOnce()).setExecutedAmount(bd("50"));
    }

    @Test
    void nasdaqFundExposureReducesQqqmRecommendationAndDoesNotAlterRealUsdPortfolio() {
        UUID planId = UUID.randomUUID();
        UUID vooId = UUID.randomUUID();
        UUID qqqmId = UUID.randomUUID();
        InvestmentPlanEntity plan = plan(planId);
        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        InvestmentPlanAssetEntity voo = planAsset(instrument("VOO", vooId), "0.5");
        InvestmentPlanAssetEntity qqqm = planAsset(instrument("QQQM", qqqmId), "0.5");
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(voo, qqqm));
        PortfolioService portfolio = mock(PortfolioService.class);
        when(portfolio.currentMarketValues()).thenReturn(Map.of(vooId, bd("100"), qqqmId, BigDecimal.ZERO));
        when(portfolio.currentValuations(anyCollection())).thenReturn(List.of(
                new PortfolioService.CurrentValuation(vooId, bd("100"), bd("100"), FreshnessStatus.FRESH),
                new PortfolioService.CurrentValuation(qqqmId, BigDecimal.ZERO, bd("100"), FreshnessStatus.FRESH)));
        CnyFundPlanProjection projection = mock(CnyFundPlanProjection.class);
        when(projection.snapshot(any(LocalDate.class))).thenReturn(new CnyFundPlanProjection.Snapshot(
                Map.of(), Map.of("QQQM", bd("200")), Set.of(), Set.of()));
        PlanService service = new PlanService(plans, assets, mock(CycleRepository.class),
                mock(CycleAssetRepository.class), mock(InstrumentRepository.class),
                mock(TransactionRepository.class), portfolio, projection, CLOCK, ZoneId.of("UTC"));

        PlanDtos.RecommendationResponse recommendation = service.recommendation(planId, bd("100"));
        Map<String, PlanDtos.RecommendationItem> bySymbol = new java.util.HashMap<>();
        recommendation.items().forEach(item -> bySymbol.put(item.symbol(), item));
        assertThat(bySymbol.get("QQQM").currentValue()).isEqualByComparingTo("200");
        assertThat(bySymbol.get("QQQM").suggestedAmount()).isEqualByComparingTo("0");
        assertThat(bySymbol.get("VOO").suggestedAmount()).isEqualByComparingTo("100");
        assertThat(recommendation.status()).isEqualTo(FreshnessStatus.FRESH);
    }

    @Test
    void unknownCnyConversionSuppressesNextDcaRecommendationInsteadOfInventingAPrice() {
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        InvestmentPlanEntity plan = plan(planId);
        InvestmentPlanCycleEntity cycle = cycle(cycleId, plan);
        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findByPlanIdAndPeriod(org.mockito.ArgumentMatchers.eq(planId),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.of(cycle));
        when(cycles.findAllByPlanIdOrderByPeriodAsc(planId)).thenReturn(List.of(cycle));
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        when(cycleAssets.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of());
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId))
                .thenReturn(List.of());
        CnyFundPlanProjection projection = mock(CnyFundPlanProjection.class);
        when(projection.snapshot(any(LocalDate.class))).thenReturn(new CnyFundPlanProjection.Snapshot(
                Map.of(AUGUST, new CnyFundPlanProjection.Month(BigDecimal.ZERO, Map.of(), false)),
                Map.of(), Set.of(), Set.of()));
        PlanService service = new PlanService(plans, mock(AssetRepository.class), cycles,
                cycleAssets, mock(InstrumentRepository.class), transactions,
                mock(PortfolioService.class), projection, CLOCK, ZoneId.of("UTC"));

        PlanDtos.CycleResponse response = service.cycle(planId, "2026-08");
        assertThat(response.dataStatus()).isEqualTo(FreshnessStatus.PARTIAL);
        PlanDtos.NextDcaResponse next = service.nextDca(planId).orElseThrow();
        assertThat(next.status()).isEqualTo(FreshnessStatus.PARTIAL);
        assertThat(next.items()).isEmpty();
        assertThat(next.amount()).isEqualByComparingTo("150");
    }

    private static InvestmentPlanEntity plan(UUID id) {
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        lenient().when(plan.getId()).thenReturn(id);
        lenient().when(plan.getStartDate()).thenReturn(LocalDate.of(2026, 8, 1));
        lenient().when(plan.getExecutionStartDay()).thenReturn(1);
        lenient().when(plan.getExecutionEndDay()).thenReturn(31);
        return plan;
    }

    private static InvestmentPlanCycleEntity cycle(UUID id, InvestmentPlanEntity plan) {
        InvestmentPlanCycleEntity cycle = mock(InvestmentPlanCycleEntity.class);
        when(cycle.getId()).thenReturn(id);
        when(cycle.getPlan()).thenReturn(plan);
        when(cycle.getPeriod()).thenReturn("2026-08");
        when(cycle.getPlannedAmount()).thenReturn(bd("150"));
        return cycle;
    }

    private static InvestmentPlanAssetEntity planAsset(InstrumentEntity instrument, String weight) {
        InvestmentPlanAssetEntity asset = mock(InvestmentPlanAssetEntity.class);
        when(asset.getInstrument()).thenReturn(instrument);
        when(asset.getTargetWeight()).thenReturn(bd(weight));
        return asset;
    }

    private static InstrumentEntity instrument(String symbol, UUID id) {
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(id);
        when(instrument.getSymbol()).thenReturn(symbol);
        return instrument;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
