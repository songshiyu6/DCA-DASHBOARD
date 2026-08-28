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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void planUpdateLeavesExecutionWindowCycleFrozenEvenWhenPersistedStatusIsUpcoming() {
        UUID planId = UUID.randomUUID();
        UUID previousCycleId = UUID.randomUUID();
        UUID currentCycleId = UUID.randomUUID();
        UUID futureCycleId = UUID.randomUUID();
        InstrumentEntity qqq = instrument("QQQ", UUID.randomUUID());
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getId()).thenReturn(planId);
        when(plan.getStatus()).thenReturn(PlanStatus.ACTIVE);
        when(plan.getMonthlyBudget()).thenReturn(bd("1000"));
        when(plan.getStartDate()).thenReturn(LocalDate.of(2026, 8, 1));
        when(plan.getExecutionStartDay()).thenReturn(1);
        when(plan.getExecutionEndDay()).thenReturn(7);

        InvestmentPlanCycleEntity previous = mock(InvestmentPlanCycleEntity.class);
        when(previous.getId()).thenReturn(previousCycleId);
        when(previous.getPlan()).thenReturn(plan);
        when(previous.getPeriod()).thenReturn("2026-07");
        when(previous.getPlannedAmount()).thenReturn(bd("1000"));
        when(previous.getStatus()).thenReturn(CycleStatus.UPCOMING);
        InvestmentPlanCycleEntity current = mock(InvestmentPlanCycleEntity.class);
        when(current.getId()).thenReturn(currentCycleId);
        when(current.getPlan()).thenReturn(plan);
        when(current.getPeriod()).thenReturn("2026-08");
        when(current.getPlannedAmount()).thenReturn(bd("1000"));
        when(current.getStatus()).thenReturn(CycleStatus.UPCOMING);
        InvestmentPlanCycleEntity future = mock(InvestmentPlanCycleEntity.class);
        when(future.getId()).thenReturn(futureCycleId);
        when(future.getPlan()).thenReturn(plan);
        when(future.getPeriod()).thenReturn("2026-09");
        when(future.getPlannedAmount()).thenReturn(bd("1000"));
        when(future.getStatus()).thenReturn(CycleStatus.UPCOMING);

        InvestmentPlanAssetEntity updatedAsset = mock(InvestmentPlanAssetEntity.class);
        when(updatedAsset.getInstrument()).thenReturn(qqq);
        when(updatedAsset.getTargetWeight()).thenReturn(BigDecimal.ONE);

        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(plans.findFirstByStatus(PlanStatus.ACTIVE)).thenReturn(Optional.of(plan));
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(updatedAsset));
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("QQQ")).thenReturn(Optional.of(qqq));
        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findAllByPlanIdOrderByPeriodAsc(planId)).thenReturn(List.of(previous, current, future));
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(previousCycleId))
                .thenReturn(List.of());
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(currentCycleId))
                .thenReturn(List.of());
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(futureCycleId))
                .thenReturn(List.of());

        PlanService service = new PlanService(plans, assets, cycles, cycleAssets, instruments, transactions,
                mock(PortfolioService.class),
                Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneId.of("UTC")), ZoneId.of("UTC"));

        service.update(planId, new PlanDtos.PlanRequest("Updated", PlanFrequency.MONTHLY, bd("2000"),
                LocalDate.of(2026, 8, 1), 1, 7, PlanStatus.ACTIVE,
                List.of(new PlanDtos.PlanAssetRequest("QQQ", BigDecimal.ONE))));

        verify(cycleAssets, never()).deleteAllByCycleId(previousCycleId);
        verify(cycleAssets, never()).deleteAllByCycleId(currentCycleId);
        verify(cycleAssets).deleteAllByCycleId(futureCycleId);
    }

    @Test
    void cycleMutabilityTreatsWindowBoundariesAsCalendarFacts() {
        InvestmentPlanEntity plan = planWithWindow(10, 20);
        InvestmentPlanCycleEntity cycle = cycle(plan, "2026-08", CycleStatus.UPCOMING);

        assertTrue(PlanService.isMutableFutureCycle(cycle, fixedDate("2026-08-09"), false));
        assertFalse(PlanService.isMutableFutureCycle(cycle, fixedDate("2026-08-10"), false));
        assertFalse(PlanService.isMutableFutureCycle(cycle, fixedDate("2026-08-20"), false));
        assertFalse(PlanService.isMutableFutureCycle(cycle, fixedDate("2026-08-21"), false));
    }

    @Test
    void cycleMutabilityClampsDayThirtyOneToShortMonthEnd() {
        InvestmentPlanEntity plan = planWithWindow(31, 31);
        InvestmentPlanCycleEntity februaryCycle = cycle(plan, "2026-02", CycleStatus.UPCOMING);

        assertTrue(PlanService.isMutableFutureCycle(februaryCycle, fixedDate("2026-02-27"), false));
        assertFalse(PlanService.isMutableFutureCycle(februaryCycle, fixedDate("2026-02-28"), false));
    }

    @Test
    void linkedTransactionFreezesCycleEvenWhenPersistedStatusIsUpcoming() {
        InvestmentPlanEntity plan = planWithWindow(1, 7);
        InvestmentPlanCycleEntity cycle = cycle(plan, "2026-09", CycleStatus.UPCOMING);

        assertFalse(PlanService.isMutableFutureCycle(cycle, fixedDate("2026-08-27"), true));
    }

    @Test
    void planUpdateDoesNotRewriteLinkedFutureCycleEvenWhenStatusIsUpcoming() {
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        InstrumentEntity qqq = instrument("QQQ", UUID.randomUUID());
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getId()).thenReturn(planId);
        when(plan.getStatus()).thenReturn(PlanStatus.ACTIVE);
        when(plan.getMonthlyBudget()).thenReturn(bd("1000"));
        when(plan.getStartDate()).thenReturn(LocalDate.of(2026, 8, 1));
        when(plan.getExecutionStartDay()).thenReturn(1);
        when(plan.getExecutionEndDay()).thenReturn(7);

        InvestmentPlanCycleEntity cycle = cycle(plan, "2026-09", CycleStatus.UPCOMING);
        when(cycle.getId()).thenReturn(cycleId);
        when(cycle.getPlannedAmount()).thenReturn(bd("1000"));
        TransactionEntity linkedBuy = new TransactionEntity();
        linkedBuy.setInstrument(qqq);
        linkedBuy.setTransactionType(TransactionType.BUY);
        linkedBuy.setTradeDate(LocalDate.of(2026, 9, 2));
        linkedBuy.setQuantity(bd("1"));
        linkedBuy.setUnitPrice(bd("500"));
        linkedBuy.setFee(BigDecimal.ZERO);

        InvestmentPlanAssetEntity updatedAsset = mock(InvestmentPlanAssetEntity.class);
        when(updatedAsset.getInstrument()).thenReturn(qqq);
        when(updatedAsset.getTargetWeight()).thenReturn(BigDecimal.ONE);
        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(plans.findFirstByStatus(PlanStatus.ACTIVE)).thenReturn(Optional.of(plan));
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(updatedAsset));
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("QQQ")).thenReturn(Optional.of(qqq));
        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findAllByPlanIdOrderByPeriodAsc(planId)).thenReturn(List.of(cycle));
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId))
                .thenReturn(List.of(linkedBuy));

        PlanService service = new PlanService(plans, assets, cycles, cycleAssets, instruments, transactions,
                mock(PortfolioService.class), CLOCK, ZoneId.of("UTC"));

        service.update(planId, new PlanDtos.PlanRequest("Updated", PlanFrequency.MONTHLY, bd("2000"),
                LocalDate.of(2026, 8, 1), 1, 7, PlanStatus.ACTIVE,
                List.of(new PlanDtos.PlanAssetRequest("QQQ", BigDecimal.ONE))));

        verify(cycleAssets, never()).deleteAllByCycleId(cycleId);
    }

    @Test
    void cycleStatusUsesWindowBoundariesInsteadOfPersistedStatus() {
        assertEquals(CycleStatus.UPCOMING, cycleStatusAt("2026-08-09", List.of()));
        assertEquals(CycleStatus.OPEN, cycleStatusAt("2026-08-10", List.of()));
        assertEquals(CycleStatus.OPEN, cycleStatusAt("2026-08-20", List.of()));
        assertEquals(CycleStatus.SKIPPED, cycleStatusAt("2026-08-21", List.of()));
    }

    @Test
    void futureLinkedTransactionDoesNotAdvanceCycleBeforeItsExecutionWindow() {
        InstrumentEntity instrument = instrument("VOO", UUID.randomUUID());
        TransactionEntity futureBuy = new TransactionEntity();
        futureBuy.setInstrument(instrument);
        futureBuy.setTransactionType(TransactionType.BUY);
        futureBuy.setTradeDate(LocalDate.of(2026, 8, 12));
        futureBuy.setQuantity(bd("2"));
        futureBuy.setUnitPrice(bd("500"));
        futureBuy.setFee(BigDecimal.ZERO);

        assertEquals(CycleStatus.UPCOMING, cycleStatusAt("2026-08-09", List.of(futureBuy)));
    }

    private static CycleStatus cycleStatusAt(String today, List<TransactionEntity> transactions) {
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        InvestmentPlanEntity plan = planWithWindow(10, 20);
        when(plan.getId()).thenReturn(planId);
        when(plan.getStartDate()).thenReturn(LocalDate.of(2026, 8, 1));

        InvestmentPlanCycleEntity cycle = cycle(plan, "2026-08", CycleStatus.UPCOMING);
        when(cycle.getId()).thenReturn(cycleId);
        when(cycle.getPlannedAmount()).thenReturn(bd("1000"));
        when(cycle.getOpenedAt()).thenReturn(null);
        when(cycle.getCompletedAt()).thenReturn(null);

        PlanRepository plans = mock(PlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        CycleRepository cycles = mock(CycleRepository.class);
        when(cycles.findByPlanIdAndPeriod(planId, "2026-08")).thenReturn(Optional.of(cycle));
        CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
        when(cycleAssets.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of());
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId))
                .thenReturn(transactions);

        PlanService service = new PlanService(plans, mock(AssetRepository.class), cycles, cycleAssets,
                mock(InstrumentRepository.class), transactionRepository, mock(PortfolioService.class),
                Clock.fixed(Instant.parse(today + "T12:00:00Z"), ZoneId.of("UTC")), ZoneId.of("UTC"));
        return service.cycle(planId, "2026-08").status();
    }

    private static InvestmentPlanEntity planWithWindow(int startDay, int endDay) {
        InvestmentPlanEntity plan = mock(InvestmentPlanEntity.class);
        when(plan.getExecutionStartDay()).thenReturn(startDay);
        when(plan.getExecutionEndDay()).thenReturn(endDay);
        return plan;
    }

    private static InvestmentPlanCycleEntity cycle(InvestmentPlanEntity plan, String period, CycleStatus status) {
        InvestmentPlanCycleEntity cycle = mock(InvestmentPlanCycleEntity.class);
        when(cycle.getPlan()).thenReturn(plan);
        when(cycle.getPeriod()).thenReturn(period);
        when(cycle.getStatus()).thenReturn(status);
        return cycle;
    }

    private static LocalDate fixedDate(String date) {
        return LocalDate.now(Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneId.of("UTC")));
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
