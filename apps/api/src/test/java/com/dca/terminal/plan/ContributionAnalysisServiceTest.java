package com.dca.terminal.plan;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.transaction.ContributionType;
import com.dca.terminal.transaction.TransactionEntity;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContributionAnalysisServiceTest {
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final CycleRepository cycleRepository = mock(CycleRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final SplitEventRepository splitRepository = mock(SplitEventRepository.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final PortfolioService portfolioService = mock(PortfolioService.class);
    private final UUID planId = UUID.randomUUID();
    private final UUID instrumentId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final InstrumentEntity instrument = instrument();
    private final InvestmentPlanEntity plan = plan();
    private ContributionAnalysisService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-01T16:00:00Z"), ZoneId.of("UTC"));
        service = new ContributionAnalysisService(planRepository, cycleRepository, transactionRepository,
                splitRepository, marketDataService, portfolioService, clock, ZoneId.of("America/New_York"));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(cycleRepository.findAllByPlanIdOrderByPeriodAsc(planId)).thenReturn(List.of(cycle()));
        when(splitRepository.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        when(marketDataService.providerPriority()).thenReturn(List.of());
        when(portfolioService.currentValuations(anyCollection())).thenReturn(List.of(
                new PortfolioService.CurrentValuation(instrumentId, null, new BigDecimal("110"), FreshnessStatus.FRESH)));
    }

    @Test
    void keepsInitialAndMonthlyDcaAsSeparateBatchesWithActualMarketAge() {
        TransactionEntity initial = buy("2026-07-01", "10", "100");
        initial.setContributionType(ContributionType.INITIAL);
        initial.setContributionPlanId(planId);
        TransactionEntity dca = buy("2026-08-01", "10", "100");
        dca.setPlanCycleId(cycleId);
        when(transactionRepository.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(
                LocalDate.of(2026, 9, 1))).thenReturn(List.of(initial, dca));

        ContributionDtos.ContributionAnalysisResponse result = service.analyze(planId);

        assertThat(result.totalInvested()).isEqualByComparingTo("2000.000000");
        assertThat(result.initial().principal()).isEqualByComparingTo("1000.000000");
        assertThat(result.initial().pnl()).isEqualByComparingTo("100.000000");
        assertThat(result.initial().returnRate()).isEqualByComparingTo("0.1");
        assertThat(result.initial().averageMarketDays()).isEqualTo(62);
        assertThat(result.dca().principal()).isEqualByComparingTo("1000.000000");
        assertThat(result.dca().averageMarketDays()).isEqualTo(31);
        assertThat(result.unclassifiedScope()).isEqualTo("ACCOUNT");
        assertThat(result.batches()).extracting(ContributionDtos.ContributionBatchResponse::period)
                .containsExactly(null, "2026-08");
    }

    @Test
    void fifoSellClosesOlderInitialMoneyBeforeDcaMoney() {
        TransactionEntity initial = buy("2026-07-01", "10", "100");
        initial.setContributionType(ContributionType.INITIAL);
        initial.setContributionPlanId(planId);
        TransactionEntity dca = buy("2026-08-01", "10", "100");
        dca.setPlanCycleId(cycleId);
        TransactionEntity sell = trade(TransactionType.SELL, "2026-08-15", "15", "120");
        when(transactionRepository.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(
                LocalDate.of(2026, 9, 1))).thenReturn(List.of(initial, dca, sell));

        ContributionDtos.ContributionAnalysisResponse result = service.analyze(planId);

        assertThat(result.initial().pnl()).isEqualByComparingTo("200.000000");
        assertThat(result.initial().value()).isEqualByComparingTo("1200.000000");
        assertThat(result.initial().averageMarketDays()).isEqualTo(45);
        assertThat(result.dca().pnl()).isEqualByComparingTo("150.000000");
        assertThat(result.dca().value()).isEqualByComparingTo("1150.000000");
        assertThat(result.dca().averageMarketDays()).isEqualTo(23);
    }

    @Test
    void unlinkedLegacyBuyStaysUnclassifiedInsteadOfBecomingInitialCapital() {
        TransactionEntity legacy = buy("2026-07-01", "10", "100");
        when(transactionRepository.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(
                LocalDate.of(2026, 9, 1))).thenReturn(List.of(legacy));
        when(portfolioService.currentValuations(anyCollection())).thenReturn(List.of());

        ContributionDtos.ContributionAnalysisResponse result = service.analyze(planId);

        assertThat(result.totalInvested()).isEqualByComparingTo("0.000000");
        assertThat(result.initial().principal()).isEqualByComparingTo("0.000000");
        assertThat(result.unclassifiedAmount()).isEqualByComparingTo("1000.000000");
        assertThat(result.unclassifiedBuys()).hasSize(1);
    }

    private InvestmentPlanEntity plan() {
        InvestmentPlanEntity value = new InvestmentPlanEntity();
        ReflectionTestUtils.setField(value, "id", planId);
        value.setName("Core");
        value.setMonthlyBudget(new BigDecimal("1000"));
        value.setStartDate(LocalDate.of(2026, 1, 1));
        return value;
    }

    private InvestmentPlanCycleEntity cycle() {
        InvestmentPlanCycleEntity value = new InvestmentPlanCycleEntity();
        ReflectionTestUtils.setField(value, "id", cycleId);
        value.setPlan(plan);
        value.setPeriod("2026-08");
        value.setPlannedAmount(new BigDecimal("1000"));
        return value;
    }

    private InstrumentEntity instrument() {
        InstrumentEntity value = new InstrumentEntity();
        ReflectionTestUtils.setField(value, "id", instrumentId);
        value.setSymbol("VOO");
        value.setName("Vanguard S&P 500 ETF");
        return value;
    }

    private TransactionEntity buy(String date, String quantity, String price) {
        return trade(TransactionType.BUY, date, quantity, price);
    }

    private TransactionEntity trade(TransactionType type, String date, String quantity, String price) {
        TransactionEntity value = new TransactionEntity();
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
        value.setInstrument(instrument);
        value.setTransactionType(type);
        value.setTradeDate(LocalDate.parse(date));
        value.setQuantity(new BigDecimal(quantity));
        value.setUnitPrice(new BigDecimal(price));
        value.setFee(BigDecimal.ZERO);
        return value;
    }
}
