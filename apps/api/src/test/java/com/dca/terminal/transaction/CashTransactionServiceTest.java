package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.PlanService;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static com.dca.terminal.transaction.TransactionDtos.TransactionRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CashTransactionServiceTest {
    @Test
    void createsAccountDepositWithoutAnInstrument() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.nextLedgerOrder()).thenReturn(12L);
        when(transactions.saveAndFlush(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc()).thenReturn(List.of());
        TransactionService service = service(mock(InstrumentRepository.class), transactions);

        TransactionEntity result = service.create(new TransactionRequest(null, TransactionType.DEPOSIT,
                LocalDate.of(2026, 9, 1), null, null, new BigDecimal("1000"), BigDecimal.ZERO,
                null, null));

        assertEquals(TransactionType.DEPOSIT, result.getTransactionType());
        assertEquals(12L, result.getLedgerOrder());
        assertNull(result.getInstrument());
        assertEquals(0, new BigDecimal("1000").compareTo(result.getAmount()));
    }

    @Test
    void rejectsAnInstrumentOnAccountCashEvents() {
        TransactionService service = service(mock(InstrumentRepository.class), mock(TransactionRepository.class));

        DomainException exception = assertThrows(DomainException.class, () -> service.create(new TransactionRequest(
                "VOO", TransactionType.WITHDRAWAL, LocalDate.of(2026, 9, 1), null, null,
                new BigDecimal("100"), BigDecimal.ZERO, null, null)));

        assertEquals("INVALID_TRANSACTION", exception.code());
    }

    @Test
    void acceptsCashOnlyCsvWithoutSymbolQuantityOrPriceColumns() throws Exception {
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.existsByImportFingerprint(any())).thenReturn(false);
        TransactionService service = service(mock(InstrumentRepository.class), transactions);
        MockMultipartFile file = new MockMultipartFile("file", "cash.csv", "text/csv",
                "date,type,amount\n2026-09-01,DEPOSIT,1000\n2026-09-02,INTEREST,2.50\n".getBytes());

        var preview = service.preview(file);

        assertEquals(2, preview.validRows());
        assertEquals(0, preview.invalidRows());
        assertTrue(preview.rows().stream().allMatch(row -> row.row().symbol().isEmpty()));
    }

    @Test
    void requiresPositiveAmountsForExternalFlows() {
        TransactionService service = service(mock(InstrumentRepository.class), mock(TransactionRepository.class));

        DomainException exception = assertThrows(DomainException.class, () -> service.create(new TransactionRequest(
                null, TransactionType.DEPOSIT, LocalDate.of(2026, 9, 1), null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null)));

        assertEquals("INVALID_TRANSACTION", exception.code());
    }

    private static TransactionService service(InstrumentRepository instruments, TransactionRepository transactions) {
        return new TransactionService(transactions, instruments, mock(SplitEventRepository.class),
                mock(MarketDataService.class), mock(PlanService.class), mock(PortfolioService.class),
                mock(PortfolioSnapshotInvalidator.class),
                Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);
    }
}
