package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.InvestmentPlanEntity;
import com.dca.terminal.plan.PlanService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.dca.terminal.transaction.TransactionDtos.TransactionRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionContributionSourceTest {
    @Test
    void recordsInitialCapitalOnlyOnThePlanStartDate() {
        UUID planId = UUID.randomUUID();
        LocalDate startDate = LocalDate.of(2026, 7, 1);
        InvestmentPlanEntity plan = new InvestmentPlanEntity();
        plan.setStartDate(startDate);
        PlanService plans = mock(PlanService.class);
        when(plans.getEntity(planId)).thenReturn(plan);

        InstrumentEntity instrument = instrument("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.nextLedgerOrder()).thenReturn(1L);
        when(transactions.saveAndFlush(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of());
        TransactionService service = service(instruments, transactions, plans);

        TransactionEntity saved = service.create(new TransactionRequest("VOO", TransactionType.BUY, startDate,
                BigDecimal.ONE, new BigDecimal("500"), null, BigDecimal.ZERO, null,
                ContributionType.INITIAL, planId, null));

        assertEquals(ContributionType.INITIAL, saved.getContributionType());
        assertEquals(planId, saved.getContributionPlanId());
        assertNull(saved.getPlanCycleId());
    }

    @Test
    void rejectsInitialCapitalAfterThePlanStartDate() {
        UUID planId = UUID.randomUUID();
        LocalDate startDate = LocalDate.of(2026, 7, 1);
        InvestmentPlanEntity plan = new InvestmentPlanEntity();
        plan.setStartDate(startDate);
        PlanService plans = mock(PlanService.class);
        when(plans.getEntity(planId)).thenReturn(plan);

        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument("VOO")));
        TransactionRepository transactions = mock(TransactionRepository.class);
        TransactionService service = service(instruments, transactions, plans);

        DomainException exception = assertThrows(DomainException.class, () -> service.create(new TransactionRequest(
                "VOO", TransactionType.BUY, startDate.plusDays(1), BigDecimal.ONE, new BigDecimal("500"), null,
                BigDecimal.ZERO, null, ContributionType.INITIAL, planId, null)));

        assertEquals("INITIAL_CONTRIBUTION_START_DATE_ONLY", exception.code());
        verify(transactions, never()).saveAndFlush(any(TransactionEntity.class));
    }

    @Test
    void requiresAPlanCycleForDcaSource() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument("VOO")));
        TransactionRepository transactions = mock(TransactionRepository.class);
        TransactionService service = service(instruments, transactions, mock(PlanService.class));

        DomainException exception = assertThrows(DomainException.class, () -> service.create(new TransactionRequest(
                "VOO", TransactionType.BUY, LocalDate.of(2026, 8, 1), BigDecimal.ONE, new BigDecimal("500"), null,
                BigDecimal.ZERO, null, ContributionType.DCA, null, null)));

        assertEquals("DCA_CONTRIBUTION_REQUIRES_CYCLE", exception.code());
        verify(transactions, never()).saveAndFlush(any(TransactionEntity.class));
    }

    private static TransactionService service(InstrumentRepository instruments, TransactionRepository transactions,
                                              PlanService plans) {
        return new TransactionService(transactions, instruments, mock(SplitEventRepository.class),
                mock(MarketDataService.class), plans, mock(PortfolioService.class),
                mock(PortfolioSnapshotInvalidator.class),
                Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneId.of("UTC")), ZoneId.of("UTC"));
    }

    private static InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(UUID.randomUUID());
        when(instrument.getSymbol()).thenReturn(symbol);
        when(instrument.getName()).thenReturn(symbol + " ETF");
        return instrument;
    }
}
