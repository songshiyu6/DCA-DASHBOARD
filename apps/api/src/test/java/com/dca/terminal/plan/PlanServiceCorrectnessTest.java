package com.dca.terminal.plan;

import com.dca.terminal.common.DomainException;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.dca.terminal.plan.PlanDtos.CycleAssetResponse;
import static com.dca.terminal.plan.PlanDtos.CycleResponse;
import com.dca.terminal.plan.AssetRepository;
import com.dca.terminal.plan.CycleAssetRepository;
import com.dca.terminal.plan.CycleRepository;
import com.dca.terminal.plan.PlanRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanServiceCorrectnessTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneId.of("UTC"));

    @Test
    void nextDcaUsesCycleRemainingAmountAfterPartialExecution() {
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getId()).thenReturn(planId);
        when(plan.getStartDate()).thenReturn(LocalDate.of(2026, 8, 1));
        when(plan.getMonthlyBudget()).thenReturn(bd("1500"));
        when(plan.getExecutionStartDay()).thenReturn(1);
        when(plan.getExecutionEndDay()).thenReturn(31);

        InvestmentPlanCycleEntity cycle = mock(InvestmentPlanCycleEntity.class);
        when(cycle.getId()).thenReturn(cycleId);
        when(cycle.getPlan()).thenReturn(plan);
        when(cycle.getPeriod()).thenReturn("2026-08");
        when(cycle.getPlannedAmount()).thenReturn(bd("1500"));
        when(cycle.getOpenedAt()).thenReturn(null);
        when(cycle.getCompletedAt()).thenReturn(null);

        InstrumentEntity instrument = instrument("VOO", instrumentId);
        InvestmentPlanAssetEntity planAsset = mock(InvestmentPlanAssetEntity.class);
        when(planAsset.getInstrument()).thenReturn(instrument);
        when(planAsset.getTargetWeight()).thenReturn(BigDecimal.ONE);
        InvestmentPlanCycleAssetEntity cycleAsset = mock(InvestmentPlanCycleAssetEntity.class);
        when(cycleAsset.getInstrument()).thenReturn(instrument);
        when(cycleAsset.getTargetWeight()).thenReturn(BigDecimal.ONE);
        when(cycleAsset.getPlannedAmount()).thenReturn(bd("1500"));

        TransactionEntity executedBuy = new TransactionEntity();
        executedBuy.setInstrument(instrument);
        executedBuy.setTransactionType(TransactionType.BUY);
        executedBuy.setTradeDate(LocalDate.of(2026, 8, 5));
        executedBuy.setQuantity(bd("2"));
        executedBuy.setUnitPrice(bd("550"));
        executedBuy.setFee(BigDecimal.ZERO);
        executedBuy.setLedgerOrder(1L);

        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findByPlanIdAndPeriod(any(UUID.class), anyString())).thenReturn(Optional.of(cycle));
        when(cycles.findAllByPlanIdOrderByPeriodAsc(planId)).thenReturn(List.of(cycle));
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        when(cycleAssets.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of(cycleAsset));
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(planAsset));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId))
                .thenReturn(List.of(executedBuy));
        PortfolioService portfolio = mock(PortfolioService.class);
        when(portfolio.currentMarketValues()).thenReturn(Map.of(instrumentId, bd("10000")));
        when(portfolio.currentValuations(anyCollection())).thenReturn(List.of(
                new PortfolioService.CurrentValuation(instrumentId, bd("10000"), bd("500"), FreshnessStatus.FRESH)));

        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        PlanService service = new PlanService(plans, assets, cycles, cycleAssets,
                mock(InstrumentRepository.class), transactions, portfolio, CLOCK, ZoneId.of("UTC"));

        var result = service.nextDca(planId).orElseThrow();

        assertEquals(0, result.amount().compareTo(bd("400.00")));
        assertEquals("2026-08", result.period());
        assertEquals(FreshnessStatus.FRESH, result.status());
    }

    @Test
    void rejectsInvalidPlanCycleLinksAndAcceptsOnlyAnInWindowBuy() {
        UUID cycleId = UUID.randomUUID();
        UUID allowedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getExecutionStartDay()).thenReturn(1);
        when(plan.getExecutionEndDay()).thenReturn(7);
        InvestmentPlanCycleEntity cycle = mock(InvestmentPlanCycleEntity.class);
        when(cycle.getPeriod()).thenReturn("2026-08");
        when(cycle.getPlan()).thenReturn(plan);
        InstrumentEntity allowed = instrument("VOO", allowedId);
        InvestmentPlanCycleAssetEntity asset = mock(InvestmentPlanCycleAssetEntity.class);
        when(asset.getInstrument()).thenReturn(allowed);

        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findById(cycleId)).thenReturn(Optional.of(cycle));
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        when(cycleAssets.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of(asset));
        PlanService service = new PlanService(mock(PlanRepository.class), mock(AssetRepository.class), cycles,
                cycleAssets, mock(InstrumentRepository.class), mock(TransactionRepository.class),
                mock(PortfolioService.class), CLOCK, ZoneId.of("UTC"));

        DomainException wrongType = assertThrows(DomainException.class,
                () -> service.validateCycleForTransaction(cycleId, allowedId, TransactionType.SELL,
                        LocalDate.of(2026, 8, 5)));
        assertEquals("PLAN_CYCLE_REQUIRES_BUY", wrongType.code());

        DomainException wrongPeriod = assertThrows(DomainException.class,
                () -> service.validateCycleForTransaction(cycleId, allowedId, TransactionType.BUY,
                        LocalDate.of(2026, 9, 1)));
        assertEquals("PLAN_CYCLE_PERIOD_MISMATCH", wrongPeriod.code());

        DomainException outsideWindow = assertThrows(DomainException.class,
                () -> service.validateCycleForTransaction(cycleId, allowedId, TransactionType.BUY,
                        LocalDate.of(2026, 8, 8)));
        assertEquals("PLAN_CYCLE_OUTSIDE_WINDOW", outsideWindow.code());

        DomainException wrongInstrument = assertThrows(DomainException.class,
                () -> service.validateCycleForTransaction(cycleId, otherId, TransactionType.BUY,
                        LocalDate.of(2026, 8, 5)));
        assertEquals("INVALID_PLAN_CYCLE", wrongInstrument.code());

        assertDoesNotThrow(() -> service.validateCycleForTransaction(cycleId, allowedId, TransactionType.BUY,
                LocalDate.of(2026, 8, 5)));
    }

    @Test
    void marksRecommendationStaleWhenItUsesAStaleValuation() {
        UUID planId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getId()).thenReturn(planId);
        when(plan.getMonthlyBudget()).thenReturn(bd("1000"));
        InstrumentEntity instrument = instrument("VOO", instrumentId);
        InvestmentPlanAssetEntity asset = mock(InvestmentPlanAssetEntity.class);
        when(asset.getInstrument()).thenReturn(instrument);
        when(asset.getTargetWeight()).thenReturn(BigDecimal.ONE);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(portfolio.currentMarketValues()).thenReturn(Map.of(instrumentId, bd("10000")));
        when(portfolio.currentValuations(anyCollection())).thenReturn(List.of(
                new PortfolioService.CurrentValuation(instrumentId, bd("10000"), bd("500"), FreshnessStatus.STALE)));
        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(asset));

        PlanService service = new PlanService(plans, assets, mock(CycleRepository.class),
                mock(CycleAssetRepository.class), mock(InstrumentRepository.class), mock(TransactionRepository.class),
                portfolio, CLOCK, ZoneId.of("UTC"));

        var response = service.recommendation(planId, bd("1000"));

        assertEquals(FreshnessStatus.STALE, response.status());
        assertTrue(response.message().contains("stale"));
        assertTrue(response.items().getFirst().suggestedAmount().signum() > 0);
    }

    @Test
    void opensAThirtyFirstDayWindowOnTheLastDayOfFebruary() {
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getId()).thenReturn(planId);
        when(plan.getStartDate()).thenReturn(LocalDate.of(2026, 1, 1));
        when(plan.getExecutionStartDay()).thenReturn(31);
        when(plan.getExecutionEndDay()).thenReturn(31);

        InvestmentPlanCycleEntity cycle = mock(InvestmentPlanCycleEntity.class);
        when(cycle.getId()).thenReturn(cycleId);
        when(cycle.getPlan()).thenReturn(plan);
        when(cycle.getPeriod()).thenReturn("2026-02");
        when(cycle.getPlannedAmount()).thenReturn(bd("1000"));
        when(cycle.getOpenedAt()).thenReturn(null);
        when(cycle.getCompletedAt()).thenReturn(null);

        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findByPlanIdAndPeriod(planId, "2026-02")).thenReturn(Optional.of(cycle));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId)).thenReturn(List.of());
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        when(cycleAssets.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of());

        PlanService service = new PlanService(plans, mock(AssetRepository.class), cycles, cycleAssets,
                mock(InstrumentRepository.class), transactions, mock(PortfolioService.class),
                Clock.fixed(Instant.parse("2026-02-28T12:00:00Z"), ZoneId.of("UTC")), ZoneId.of("UTC"));

        CycleResponse response = service.cycle(planId, "2026-02");

        assertEquals(CycleStatus.OPEN, response.status());
    }

    private static InstrumentEntity instrument(String symbol, UUID id) {
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getSymbol()).thenReturn(symbol);
        when(instrument.getName()).thenReturn(symbol + " ETF");
        when(instrument.getId()).thenReturn(id);
        return instrument;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
