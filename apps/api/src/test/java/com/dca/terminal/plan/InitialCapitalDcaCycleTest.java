package com.dca.terminal.plan;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.transaction.ContributionType;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InitialCapitalDcaCycleTest {
    private final UUID planId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final AssetRepository assetRepository = mock(AssetRepository.class);
    private final CycleRepository cycleRepository = mock(CycleRepository.class);
    private final CycleAssetRepository cycleAssetRepository = mock(CycleAssetRepository.class);
    private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final PortfolioService portfolioService = mock(PortfolioService.class);
    private final InvestmentPlanEntity plan = plan();
    private final InvestmentPlanCycleEntity cycle = cycle();
    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(planRepository, assetRepository, cycleRepository, cycleAssetRepository,
                instrumentRepository, transactionRepository, portfolioService,
                Clock.fixed(Instant.parse("2026-01-03T12:00:00Z"), ZoneId.of("UTC")), ZoneId.of("UTC"));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(cycleRepository.findByPlanIdAndPeriod(planId, "2026-01")).thenReturn(Optional.of(cycle));
        when(cycleRepository.findById(cycleId)).thenReturn(Optional.of(cycle));
        when(transactionRepository.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId))
                .thenReturn(List.of());
        when(cycleAssetRepository.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of());
    }

    @Test
    void actualInitialCapitalMakesThatMonthASkippedZeroBudgetDcaCycle() {
        when(transactionRepository.existsByContributionTypeAndContributionPlanIdAndTradeDateBetween(
                ContributionType.INITIAL, planId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(true);

        PlanDtos.CycleResponse result = service.cycle(planId, "2026-01");

        assertThat(result.status()).isEqualTo(CycleStatus.SKIPPED);
        assertThat(result.plannedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.executedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void dcaBuyIsRejectedWhenTheCycleMonthContainsInitialCapital() {
        when(transactionRepository.existsByContributionTypeAndContributionPlanIdAndTradeDateBetween(
                ContributionType.INITIAL, planId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(true);

        DomainException exception = assertThrows(DomainException.class, () -> service.validateCycleForTransaction(
                cycleId, UUID.randomUUID(), TransactionType.BUY, LocalDate.of(2026, 1, 3)));

        assertThat(exception.code()).isEqualTo("INITIAL_CAPITAL_MONTH_SKIPS_DCA");
    }

    @Test
    void startMonthStillRunsNormallyWhenThereIsNoInitialCapitalTransaction() {
        when(transactionRepository.existsByContributionTypeAndContributionPlanIdAndTradeDateBetween(
                ContributionType.INITIAL, planId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(false);

        PlanDtos.CycleResponse result = service.cycle(planId, "2026-01");

        assertThat(result.status()).isEqualTo(CycleStatus.OPEN);
        assertThat(result.plannedAmount()).isEqualByComparingTo("1500.00");
    }

    private InvestmentPlanEntity plan() {
        InvestmentPlanEntity value = new InvestmentPlanEntity();
        ReflectionTestUtils.setField(value, "id", planId);
        value.setName("Core");
        value.setMonthlyBudget(new BigDecimal("1500.00"));
        value.setStartDate(LocalDate.of(2026, 1, 1));
        value.setExecutionStartDay(1);
        value.setExecutionEndDay(7);
        value.setStatus(PlanStatus.ACTIVE);
        return value;
    }

    private InvestmentPlanCycleEntity cycle() {
        InvestmentPlanCycleEntity value = new InvestmentPlanCycleEntity();
        ReflectionTestUtils.setField(value, "id", cycleId);
        value.setPlan(plan);
        value.setPeriod("2026-01");
        value.setPlannedAmount(new BigDecimal("1500.00"));
        value.setExecutedAmount(BigDecimal.ZERO);
        value.setStatus(CycleStatus.UPCOMING);
        return value;
    }
}
