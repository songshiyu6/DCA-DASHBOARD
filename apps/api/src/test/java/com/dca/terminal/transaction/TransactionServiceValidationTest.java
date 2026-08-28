package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.PlanService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.portfolio.PortfolioService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static com.dca.terminal.transaction.TransactionDtos.CsvCommitRequest;
import static com.dca.terminal.transaction.TransactionDtos.CsvRowRequest;
import static com.dca.terminal.transaction.TransactionDtos.TransactionRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionServiceValidationTest {
    @Test
    void rejectsFeeFieldOnDividendBeforeItCanDisappearFromCalculations() {
        TransactionService service = service(mock(InstrumentRepository.class), mock(TransactionRepository.class),
                mock(PlanService.class), mock(PortfolioService.class));
        TransactionRequest request = new TransactionRequest("VOO", TransactionType.DIVIDEND,
                java.time.LocalDate.of(2026, 8, 1), null, null, new java.math.BigDecimal("25"),
                new java.math.BigDecimal("1"), null, null);

        DomainException exception = assertThrows(DomainException.class, () -> service.create(request));

        assertEquals("INVALID_TRANSACTION", exception.code());
    }

    @Test
    void rejectsASecondCommitAfterTheSameCsvFingerprintWasImported() throws IOException {
        InstrumentEntity instrument = instrument("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.existsByImportFingerprint(anyString())).thenReturn(false);
        TransactionService service = service(instruments, transactions, mock(PlanService.class), mock(PortfolioService.class));
        MockMultipartFile file = new MockMultipartFile("file", "transactions.csv", "text/csv",
                "date,type,symbol,quantity,price,fee\n2026-08-01,BUY,VOO,1,500,0\n".getBytes());

        var preview = service.preview(file);
        when(transactions.existsByImportFingerprint(anyString())).thenReturn(true);

        DomainException exception = assertThrows(DomainException.class,
                () -> service.commit(new CsvCommitRequest(preview.batchId(), List.of(preview.rows().getFirst().row()))));

        assertEquals("INVALID_CSV", exception.code());
        assertTrue(exception.getMessage().contains("already imported"));
    }

    @Test
    void marksDuplicateRowsWithinOnePreviewAndKeepsFingerprintStableAcrossPreviews() throws IOException {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        InstrumentEntity voo = instrument("VOO");
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(voo));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.existsByImportFingerprint(anyString())).thenReturn(false);
        TransactionService service = service(instruments, transactions, mock(PlanService.class), mock(PortfolioService.class));
        byte[] csv = "date,type,symbol,quantity,price,fee\n2026-08-01,BUY,VOO,1,500,0\n2026-08-01,BUY,VOO,1.0,500.00,0.0\n".getBytes();

        var first = service.preview(new MockMultipartFile("file", "transactions.csv", "text/csv", csv));
        var second = service.preview(new MockMultipartFile("file", "transactions.csv", "text/csv", csv));

        assertEquals(1, first.validRows());
        assertEquals(1, first.invalidRows());
        assertEquals(first.rows().getFirst().fingerprint(), second.rows().getFirst().fingerprint());
        assertTrue(first.rows().get(1).errors().stream().anyMatch(error -> error.contains("Duplicate")));
    }

    @Test
    void validatesPlanCycleBeforePersistingLinkedTransactions() {
        InstrumentEntity instrument = instrument("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        PlanService plans = mock(PlanService.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findTopByOrderByLedgerOrderDesc()).thenReturn(Optional.empty());
        when(transactions.saveAndFlush(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of());
        TransactionService service = service(instruments, transactions, plans, mock(PortfolioService.class));
        UUID cycleId = UUID.randomUUID();
        TransactionRequest request = new TransactionRequest("VOO", TransactionType.BUY,
                java.time.LocalDate.of(2026, 8, 5), new java.math.BigDecimal("1"), new java.math.BigDecimal("500"),
                null, java.math.BigDecimal.ZERO, cycleId, null);

        service.create(request);

        verify(plans).validateCycleForTransaction(cycleId, instrument.getId(), TransactionType.BUY,
                java.time.LocalDate.of(2026, 8, 5));
    }

    @Test
    void invalidatesFromCreatedTradeDateAfterLedgerValidation() {
        InstrumentEntity instrument = instrument("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.saveAndFlush(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of());
        PortfolioSnapshotInvalidator invalidator = mock(PortfolioSnapshotInvalidator.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        TransactionService service = service(instruments, transactions, mock(PlanService.class), portfolio, invalidator);
        java.time.LocalDate tradeDate = java.time.LocalDate.of(2026, 8, 1);

        service.create(new TransactionRequest("VOO", TransactionType.BUY, tradeDate,
                new java.math.BigDecimal("1"), new java.math.BigDecimal("100"), null,
                java.math.BigDecimal.ZERO, null, null));

        verify(invalidator).invalidateFrom(tradeDate);
        verify(portfolio).rebuildTodaySnapshot();
    }

    @Test
    void updateInvalidatesFromTheEarlierOfTheOldAndNewTradeDates() {
        InstrumentEntity instrument = instrument("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        TransactionEntity existing = new TransactionEntity();
        existing.setInstrument(instrument);
        existing.setTransactionType(TransactionType.BUY);
        existing.setTradeDate(java.time.LocalDate.of(2026, 8, 20));
        existing.setQuantity(new java.math.BigDecimal("1"));
        existing.setUnitPrice(new java.math.BigDecimal("100"));
        existing.setFee(java.math.BigDecimal.ZERO);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findById(any())).thenReturn(Optional.of(existing));
        when(transactions.saveAndFlush(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of());
        PortfolioSnapshotInvalidator invalidator = mock(PortfolioSnapshotInvalidator.class);
        TransactionService service = service(instruments, transactions, mock(PlanService.class),
                mock(PortfolioService.class), invalidator);
        java.time.LocalDate newDate = java.time.LocalDate.of(2026, 8, 5);

        service.update(UUID.randomUUID(), new TransactionRequest("VOO", TransactionType.BUY, newDate,
                new java.math.BigDecimal("1"), new java.math.BigDecimal("100"), null,
                java.math.BigDecimal.ZERO, null, null));

        verify(invalidator).invalidateFrom(newDate);
    }

    @Test
    void deleteInvalidatesFromTheDeletedTradeDate() {
        InstrumentEntity instrument = instrument("VOO");
        TransactionEntity existing = new TransactionEntity();
        existing.setInstrument(instrument);
        existing.setTransactionType(TransactionType.BUY);
        java.time.LocalDate tradeDate = java.time.LocalDate.of(2026, 8, 10);
        existing.setTradeDate(tradeDate);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findById(any())).thenReturn(Optional.of(existing));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of());
        PortfolioSnapshotInvalidator invalidator = mock(PortfolioSnapshotInvalidator.class);
        TransactionService service = service(mock(InstrumentRepository.class), transactions, mock(PlanService.class),
                mock(PortfolioService.class), invalidator);

        service.delete(UUID.randomUUID());

        verify(transactions).delete(existing);
        verify(invalidator).invalidateFrom(tradeDate);
    }

    @Test
    void csvCommitInvalidatesFromTheEarliestImportedTradeDate() {
        InstrumentEntity instrument = instrument("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.save(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of());
        PortfolioSnapshotInvalidator invalidator = mock(PortfolioSnapshotInvalidator.class);
        TransactionService service = service(instruments, transactions, mock(PlanService.class),
                mock(PortfolioService.class), invalidator);
        UUID batchId = UUID.randomUUID();

        service.commit(new CsvCommitRequest(batchId, List.of(
                new CsvRowRequest("2026-08-20", "BUY", "VOO", "1", "100", "0", null, null, null),
                new CsvRowRequest("2026-08-05", "BUY", "VOO", "1", "100", "0", null, null, null))));

        verify(invalidator).invalidateFrom(java.time.LocalDate.of(2026, 8, 5));
    }

    @Test
    void invalidCsvBatchDoesNotPersistOrInvalidate() {
        InstrumentEntity instrument = instrument("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findBySymbolIgnoreCase("VOO")).thenReturn(Optional.of(instrument));
        TransactionRepository transactions = mock(TransactionRepository.class);
        PortfolioSnapshotInvalidator invalidator = mock(PortfolioSnapshotInvalidator.class);
        TransactionService service = service(instruments, transactions, mock(PlanService.class),
                mock(PortfolioService.class), invalidator);

        assertThrows(DomainException.class, () -> service.commit(new CsvCommitRequest(UUID.randomUUID(), List.of(
                new CsvRowRequest("2026-08-05", "BUY", "VOO", "1", "100", "0", null, null, null),
                new CsvRowRequest("2026-08-06", "INVALID", "VOO", "1", "100", "0", null, null, null)))));

        verify(transactions, never()).save(any(TransactionEntity.class));
        verify(invalidator, never()).invalidateFrom(any());
    }

    private static TransactionService service(InstrumentRepository instruments, TransactionRepository transactions,
                                               PlanService plans, PortfolioService portfolio) {
        return service(instruments, transactions, plans, portfolio, mock(PortfolioSnapshotInvalidator.class));
    }

    private static TransactionService service(InstrumentRepository instruments, TransactionRepository transactions,
                                               PlanService plans, PortfolioService portfolio,
                                               PortfolioSnapshotInvalidator invalidator) {
        return new TransactionService(transactions, instruments, mock(SplitEventRepository.class),
                mock(MarketDataService.class), plans, portfolio, invalidator);
    }

    private static InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(UUID.randomUUID());
        when(instrument.getSymbol()).thenReturn(symbol);
        when(instrument.getName()).thenReturn(symbol + " ETF");
        return instrument;
    }
}
