package com.dca.terminal.plan;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.*;
import com.dca.terminal.portfolio.*;
import com.dca.terminal.transaction.*;
import com.dca.terminal.marketdata.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;

class LeveragedPlanExecutionTest {
    final UUID planId = UUID.randomUUID(), cycleId = UUID.randomUUID();
    final InstrumentEntity qqqm = instrument("QQQM"), tqqq = instrument("TQQQ"), voo = instrument("VOO");
    final InstrumentRepository instruments = mock(InstrumentRepository.class);
    final PlanRepository plans = mock(PlanRepository.class);
    final AssetRepository assets = mock(AssetRepository.class);
    final CycleRepository cycles = mock(CycleRepository.class);
    final CycleAssetRepository cycleAssets = mock(CycleAssetRepository.class);
    final TransactionRepository transactions = mock(TransactionRepository.class);
    final PortfolioService portfolio = mock(PortfolioService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC);
    final PlanService service = new PlanService(plans, assets, cycles, cycleAssets, instruments, transactions,
            portfolio, mock(CnyFundPlanProjection.class), clock, ZoneOffset.UTC);

    LeveragedPlanExecutionTest() {
        var plan = mock(InvestmentPlanEntity.class);
        when(plan.getId()).thenReturn(planId);
        when(plan.getStartDate()).thenReturn(LocalDate.parse("2026-09-01"));
        when(plan.getExecutionStartDay()).thenReturn(1);
        when(plan.getExecutionEndDay()).thenReturn(31);
        var cycle = mock(InvestmentPlanCycleEntity.class);
        when(cycle.getPlan()).thenReturn(plan);
        when(cycle.getId()).thenReturn(cycleId);
        when(cycle.getPeriod()).thenReturn("2026-11");
        when(cycle.getPlannedAmount()).thenReturn(new BigDecimal("4000"));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(cycles.findById(cycleId)).thenReturn(Optional.of(cycle));
        when(cycles.findByPlanIdAndPeriod(planId,"2026-11")).thenReturn(Optional.of(cycle));
        var asset = mock(InvestmentPlanCycleAssetEntity.class);
        when(asset.getInstrument()).thenReturn(qqqm);
        when(asset.getPlannedAmount()).thenReturn(new BigDecimal("4000"));
        when(asset.getTargetWeight()).thenReturn(BigDecimal.ONE);
        when(cycleAssets.findAllByCycleIdOrderByIdAsc(cycleId)).thenReturn(List.of(asset));
        when(instruments.findById(tqqq.getId())).thenReturn(Optional.of(tqqq));
        when(instruments.findById(voo.getId())).thenReturn(Optional.of(voo));
    }

    @Test void acceptsTqqqForQqqmWithoutAcceptingUnrelatedOrInverseAssets() {
        assertThatCode(() -> service.validateCycleForTransaction(cycleId,tqqq.getId(),TransactionType.BUY,
                LocalDate.parse("2026-11-06"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateCycleForTransaction(cycleId,voo.getId(),TransactionType.BUY,
                LocalDate.parse("2026-11-06"))).isInstanceOf(DomainException.class);
        assertThat(PlanAssetEquivalence.resolve(instrument("SQQQ"), List.of(qqqm))).isEmpty();
        assertThat(PlanAssetEquivalence.resolve(instrument("QLD"), List.of(qqqm)).orElseThrow().multiplier())
                .isEqualByComparingTo("2");
        assertThat(PlanAssetEquivalence.resolve(instrument("QQQ"), List.of(qqqm)).orElseThrow().multiplier())
                .isEqualByComparingTo("1");
    }

    @Test void keepsPeriodAndBuyOnlyValidationForEquivalentEtfs() {
        assertThatThrownBy(() -> service.validateCycleForTransaction(cycleId,tqqq.getId(),TransactionType.BUY,
                LocalDate.parse("2026-10-06"))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.validateCycleForTransaction(cycleId,tqqq.getId(),TransactionType.SELL,
                LocalDate.parse("2026-11-06"))).isInstanceOf(DomainException.class);
    }

    @Test void countsActualCashOnceAndReportsTripleNotionalSeparately() {
        var buy = buy(tqqq, "60", "52.08", "1");
        when(transactions.findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycleId)).thenReturn(List.of(buy));
        var response = service.cycle(planId,"2026-11");
        assertThat(response.executedAmount()).isEqualByComparingTo("3125.80");
        var asset = response.assets().getFirst();
        assertThat(asset.executedAmount()).isEqualByComparingTo("3125.80");
        assertThat(asset.equivalentExposureAmount()).isEqualByComparingTo("9374.40");
        assertThat(asset.executions().getFirst().symbol()).isEqualTo("TQQQ");
        assertThat(response.status()).isEqualTo(CycleStatus.PARTIAL);
        assertThat(buy.getInstrument()).isSameAs(tqqq);
    }

    @Test void anExistingBuyCanBeReclassifiedWithoutChangingItsInstrument() {
        var buy = buy(tqqq,"60","52.08","0");
        var id = UUID.randomUUID();
        when(transactions.findById(id)).thenReturn(Optional.of(buy));
        when(instruments.findBySymbolIgnoreCase("TQQQ")).thenReturn(Optional.of(tqqq));
        when(transactions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var txService = new TransactionService(transactions,instruments,mock(SplitEventRepository.class),
                mock(MarketDataService.class),service,portfolio,mock(PortfolioSnapshotInvalidator.class),clock,ZoneOffset.UTC);
        var saved = txService.update(id,new TransactionDtos.TransactionRequest("TQQQ",TransactionType.BUY,
                LocalDate.parse("2026-11-06"),new BigDecimal("60"),new BigDecimal("52.08"),null,BigDecimal.ZERO,
                cycleId,ContributionType.DCA,null,null));
        assertThat(saved.getInstrument()).isSameAs(tqqq);
        assertThat(saved.getPlanCycleId()).isEqualTo(cycleId);
        assertThat(saved.getContributionType()).isEqualTo(ContributionType.DCA);
    }

    @Test void recommendationsIncludeLeveragedExposureButIgnoreUnrelatedHoldings() {
        var asset = mock(InvestmentPlanAssetEntity.class);
        when(asset.getInstrument()).thenReturn(qqqm);when(asset.getTargetWeight()).thenReturn(BigDecimal.ONE);
        when(assets.findAllByPlanIdOrderByIdAsc(planId)).thenReturn(List.of(asset));
        var values = Map.of(qqqm.getId(),new BigDecimal("100"),tqqq.getId(),new BigDecimal("200"),voo.getId(),new BigDecimal("1000"));
        when(portfolio.currentMarketValues()).thenReturn(values);
        when(instruments.findAllById(anyCollection())).thenReturn(List.of(tqqq,voo));
        var valuation = new PortfolioService.CurrentValuation(qqqm.getId(),new BigDecimal("100"),new BigDecimal("100"),FreshnessStatus.FRESH);
        when(portfolio.currentValuations(anyCollection())).thenReturn(List.of(valuation));
        assertThat(service.recommendation(planId,BigDecimal.TEN).items().getFirst().currentValue()).isEqualByComparingTo("700");
    }

    @Test void ambiguousTargetsNeverDuplicateLeveragedExecution() {
        assertThatThrownBy(() -> PlanAssetEquivalence.resolve(tqqq,List.of(qqqm,instrument("QQQ"))))
                .isInstanceOf(DomainException.class);
        assertThat(PlanAssetEquivalence.resolve(tqqq,List.of(tqqq,qqqm)).orElseThrow().multiplier()).isEqualByComparingTo("1");
    }

    static InstrumentEntity instrument(String symbol) {
        var i=mock(InstrumentEntity.class);when(i.getId()).thenReturn(UUID.randomUUID());when(i.getSymbol()).thenReturn(symbol);return i;
    }
    static TransactionEntity buy(InstrumentEntity i,String qty,String price,String fee) {
        var t=new TransactionEntity();t.setInstrument(i);t.setTransactionType(TransactionType.BUY);
        t.setTradeDate(LocalDate.parse("2026-11-06"));t.setQuantity(new BigDecimal(qty));
        t.setUnitPrice(new BigDecimal(price));t.setFee(new BigDecimal(fee));return t;
    }
}
