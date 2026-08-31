package com.dca.terminal.plan;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.transaction.ContributionType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.dca.terminal.plan.ContributionDtos.ClassificationCommitRequest;
import static com.dca.terminal.plan.ContributionDtos.ClassificationItemRequest;
import static com.dca.terminal.plan.ContributionDtos.ClassificationPreviewRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContributionClassificationServiceTest {
    private final PlanRepository plans = mock(PlanRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final ContributionClassificationAuditRepository audits = mock(ContributionClassificationAuditRepository.class);
    private final UUID planId = UUID.randomUUID();
    private final InvestmentPlanEntity plan = plan();
    private ContributionClassificationService service;

    @BeforeEach
    void setUp() {
        service = new ContributionClassificationService(plans, transactions, audits,
                Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
    }

    @Test
    void previewsMixedInitialAndUnplannedSelectionsWithoutWriting() {
        TransactionEntity opening = buy(LocalDate.of(2026, 1, 1));
        TransactionEntity legacy = buy(LocalDate.of(2026, 6, 1));
        when(transactions.findAllById(anyCollection())).thenReturn(List.of(opening, legacy));

        var result = service.preview(planId, new ClassificationPreviewRequest(List.of(
                new ClassificationItemRequest(opening.getId(), ContributionType.INITIAL),
                new ClassificationItemRequest(legacy.getId(), ContributionType.UNPLANNED))));

        assertThat(result.valid()).isTrue();
        assertThat(result.previewHash()).hasSize(64);
        assertThat(result.items()).extracting(item -> item.principal().toPlainString())
                .containsExactly("1000.000000", "1000.000000");
        verify(transactions, never()).saveAllAndFlush(anyCollection());
        verify(audits, never()).saveAllAndFlush(anyCollection());
    }

    @Test
    void rejectsInitialClassificationOutsideTheOpeningDateWithStableCode() {
        TransactionEntity legacy = buy(LocalDate.of(2026, 6, 1));
        when(transactions.findAllById(anyCollection())).thenReturn(List.of(legacy));

        var result = service.preview(planId, new ClassificationPreviewRequest(List.of(
                new ClassificationItemRequest(legacy.getId(), ContributionType.INITIAL))));

        assertThat(result.valid()).isFalse();
        assertThat(result.previewHash()).isNull();
        assertThat(result.items().getFirst().errors()).extracting(ContributionDtos.ClassificationError::code)
                .containsExactly("INITIAL_CONTRIBUTION_START_DATE_ONLY");
    }

    @Test
    void commitsEveryValidatedRowAndAuditRecordInOneServiceTransaction() {
        TransactionEntity opening = buy(LocalDate.of(2026, 1, 1));
        TransactionEntity legacy = buy(LocalDate.of(2026, 6, 1));
        List<ClassificationItemRequest> items = List.of(
                new ClassificationItemRequest(opening.getId(), ContributionType.INITIAL),
                new ClassificationItemRequest(legacy.getId(), ContributionType.UNPLANNED));
        when(transactions.findAllById(anyCollection())).thenReturn(List.of(opening, legacy));
        String hash = service.preview(planId, new ClassificationPreviewRequest(items)).previewHash();
        when(transactions.findAllByIdInForUpdate(List.of(opening.getId(), legacy.getId())))
                .thenReturn(List.of(opening, legacy));

        var result = service.commit(planId, new ClassificationCommitRequest(hash, items));

        assertThat(result.transactionIds()).containsExactly(opening.getId(), legacy.getId());
        assertThat(opening.getContributionType()).isEqualTo(ContributionType.INITIAL);
        assertThat(opening.getContributionPlanId()).isEqualTo(planId);
        assertThat(legacy.getContributionType()).isEqualTo(ContributionType.UNPLANNED);
        assertThat(legacy.getContributionPlanId()).isNull();
        ArgumentCaptor<Iterable<ContributionClassificationAuditEntity>> auditCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(audits).saveAllAndFlush(auditCaptor.capture());
        assertThat(auditCaptor.getValue()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getBatchId()).isEqualTo(result.batchId());
            assertThat(row.getPlanId()).isEqualTo(planId);
            assertThat(row.getCreatedAt()).isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
        });
    }

    @Test
    void stalePreviewWritesNeitherTransactionsNorAudit() {
        TransactionEntity opening = buy(LocalDate.of(2026, 1, 1));
        List<ClassificationItemRequest> items = List.of(
                new ClassificationItemRequest(opening.getId(), ContributionType.INITIAL));
        when(transactions.findAllByIdInForUpdate(List.of(opening.getId()))).thenReturn(List.of(opening));

        DomainException exception = assertThrows(DomainException.class,
                () -> service.commit(planId, new ClassificationCommitRequest("stale", items)));

        assertThat(exception.code()).isEqualTo("CONTRIBUTION_PREVIEW_STALE");
        verify(transactions, never()).saveAllAndFlush(anyCollection());
        verify(audits, never()).saveAllAndFlush(anyCollection());
    }

    private InvestmentPlanEntity plan() {
        InvestmentPlanEntity value = new InvestmentPlanEntity();
        ReflectionTestUtils.setField(value, "id", planId);
        value.setStartDate(LocalDate.of(2026, 1, 1));
        return value;
    }

    private TransactionEntity buy(LocalDate date) {
        InstrumentEntity instrument = new InstrumentEntity();
        ReflectionTestUtils.setField(instrument, "id", UUID.randomUUID());
        instrument.setSymbol("VOO");
        instrument.setName("Vanguard S&P 500 ETF");
        TransactionEntity value = new TransactionEntity();
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(value, "updatedAt", Instant.parse("2026-08-31T12:00:00Z"));
        value.setInstrument(instrument);
        value.setTransactionType(TransactionType.BUY);
        value.setTradeDate(date);
        value.setQuantity(new BigDecimal("10"));
        value.setUnitPrice(new BigDecimal("100"));
        value.setFee(BigDecimal.ZERO);
        return value;
    }
}
