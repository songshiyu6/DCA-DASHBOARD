package com.dca.terminal.plan;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.dca.terminal.plan.PlanDtos.RecommendationItem;
import com.dca.terminal.plan.AssetRepository;
import com.dca.terminal.plan.CycleAssetRepository;
import com.dca.terminal.plan.CycleRepository;
import com.dca.terminal.plan.PlanRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanServiceTest {

    @Test
    void allocatesOnlyPositiveGapsAndSumsExactlyToContribution() {
        UUID vooId = UUID.randomUUID();
        UUID qqqId = UUID.randomUUID();
        UUID schdId = UUID.randomUUID();
        InstrumentEntity voo = instrument("VOO", vooId);
        InstrumentEntity qqq = instrument("QQQ", qqqId);
        InstrumentEntity schd = instrument("SCHD", schdId);
        UUID planId = UUID.randomUUID();
        InvestmentPlanEntity plan = mockPlan(planId, "1000");
        List<InvestmentPlanAssetEntity> assets = List.of(
                asset(plan, voo, "0.50"), asset(plan, qqq, "0.30"), asset(plan, schd, "0.20"));
        PortfolioService portfolio = mock(PortfolioService.class);
        when(portfolio.currentMarketValues()).thenReturn(Map.of(
                vooId, bd("60000"), qqqId, bd("25000"), schdId, bd("15000"), UUID.randomUUID(), bd("10000")));
        when(portfolio.currentValuations(anyCollection())).thenReturn(List.of(
                new PortfolioService.CurrentValuation(vooId, bd("60000"), bd("500"), FreshnessStatus.FRESH),
                new PortfolioService.CurrentValuation(qqqId, bd("25000"), bd("500"), FreshnessStatus.FRESH),
                new PortfolioService.CurrentValuation(schdId, bd("15000"), bd("500"), FreshnessStatus.FRESH)));
        PlanService service = service(planId, plan, assets, portfolio);

        var response = service.recommendation(planId, bd("1000"));

        Map<String, RecommendationItem> bySymbol = response.items().stream()
                .collect(java.util.stream.Collectors.toMap(RecommendationItem::symbol, item -> item));
        assertEquals(FreshnessStatus.FRESH, response.status());
        assertEquals(0, bySymbol.get("VOO").suggestedAmount().compareTo(BigDecimal.ZERO));
        assertEquals("OVERWEIGHT", bySymbol.get("VOO").reason());
        assertEquals(0, bySymbol.get("VOO").currentWeight().compareTo(bd("0.60")),
                "holdings outside this plan must not change the plan denominator");
        BigDecimal total = response.items().stream().map(RecommendationItem::suggestedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, total.compareTo(bd("1000.00")));
        assertTrue(bySymbol.get("QQQ").suggestedAmount().signum() > 0);
        assertTrue(bySymbol.get("SCHD").suggestedAmount().signum() > 0);
    }

    @Test
    void disablesRecommendationWhenAPlanAssetHasNoReliablePrice() {
        UUID vooId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        InstrumentEntity voo = instrument("VOO", vooId);
        InvestmentPlanEntity plan = mockPlan(planId, "1000");
        List<InvestmentPlanAssetEntity> assets = List.of(asset(plan, voo, "1.00"));
        PortfolioService portfolio = mock(PortfolioService.class);
        when(portfolio.currentMarketValues()).thenReturn(Map.of(vooId, bd("10000")));
        when(portfolio.currentValuations(anyCollection())).thenReturn(List.of(
                new PortfolioService.CurrentValuation(vooId, bd("10000"), null, FreshnessStatus.UNAVAILABLE)));

        var response = service(planId, plan, assets, portfolio).recommendation(planId, bd("1000"));

        assertEquals(FreshnessStatus.PARTIAL, response.status());
        assertEquals(0, response.items().get(0).suggestedAmount().compareTo(BigDecimal.ZERO.setScale(2)));
        assertEquals("PRICE_UNAVAILABLE", response.items().get(0).reason());
    }

    private static PlanService service(UUID planId, InvestmentPlanEntity plan, List<InvestmentPlanAssetEntity> assets,
                                        PortfolioService portfolio) {
        PlanRepository planRepository = mock(PlanRepository.class);
        AssetRepository assetRepository = mock(AssetRepository.class);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(assetRepository.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(assets);
        return new PlanService(planRepository, assetRepository, mock(CycleRepository.class),
                mock(CycleAssetRepository.class), mock(InstrumentRepository.class), mock(TransactionRepository.class),
                portfolio, Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneId.of("UTC")), ZoneId.of("UTC"));
    }

    private static InvestmentPlanEntity mockPlan(UUID id, String budget) {
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getId()).thenReturn(id);
        when(plan.getMonthlyBudget()).thenReturn(bd(budget));
        return plan;
    }

    private static InvestmentPlanAssetEntity asset(InvestmentPlanEntity plan, InstrumentEntity instrument, String weight) {
        InvestmentPlanAssetEntity asset = mock(InvestmentPlanAssetEntity.class);
        when(asset.getPlan()).thenReturn(plan);
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
