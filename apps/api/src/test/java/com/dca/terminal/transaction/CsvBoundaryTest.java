package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.PlanService;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CsvBoundaryTest {
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final TransactionService service = new TransactionService(transactions, mock(InstrumentRepository.class),
            mock(SplitEventRepository.class), mock(MarketDataService.class), mock(PlanService.class),
            mock(PortfolioService.class), mock(PortfolioSnapshotInvalidator.class),
            Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

    @Test
    void duplicateHeadersAndAliasesCannotSilentlyReplaceAmounts() {
        for (String headers : List.of("date,type,amount,AMOUNT", "date,type,amount,planCycleId,plan_cycle_id")) {
            assertThatThrownBy(() -> preview(headers + "\n2026-08-01,DEPOSIT,100,200\n"))
                    .isInstanceOf(DomainException.class).hasMessageContaining("Duplicate CSV column");
        }
    }

    @Test
    void extraOrMissingCellsAreRejected() {
        assertThat(preview("date,type,amount\n2026-08-01,DEPOSIT,100,200\n").invalidRows()).isEqualTo(1);
        assertThat(preview("date,type,amount,notes\n2026-08-01,DEPOSIT,100\n").invalidRows()).isEqualTo(1);
    }

    @Test
    void quotedNotesCanContainCommasEscapedQuotesAndNewlines() {
        var result = preview("date,type,amount,notes\r\n2026-08-01,DEPOSIT,100,\"first, \"\"quoted\"\"\nsecond\"\r\n");
        assertThat(result.validRows()).isEqualTo(1);
        assertThat(result.rows().getFirst().row().notes()).isEqualTo("first, \"quoted\"\nsecond");
    }

    @Test
    void acceptsBomBeforeAQuotedHeader() {
        assertThat(preview("\uFEFF\"date\",type,amount\n2026-08-01,DEPOSIT,100\n").validRows()).isEqualTo(1);
    }

    @Test
    void dividendAndFeeCannotSilentlyDiscardTradeFields() {
        for (String type : List.of("DIVIDEND", "FEE")) {
            assertThat(preview("date,type,amount,quantity,price\n2026-08-01," + type + ",100,1,50\n")
                    .invalidRows()).isEqualTo(1);
        }
    }

    @Test
    void malformedQuotesCannotChangeANumericCell() {
        assertThatThrownBy(() -> preview("date,type,amount\n2026-08-01,DEPOSIT,1\"0\"0\n"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void outOfRangeAndOverpreciseNumbersAreInvalidBeforePersistence() {
        for (String amount : List.of("1e1000", "0.0000001", "100000000000000")) {
            assertThat(preview("date,type,amount\n2026-08-01,DEPOSIT," + amount + "\n").invalidRows()).isEqualTo(1);
        }
        assertThat(preview("date,type,amount\n2026-08-01,DEPOSIT,100.00000000\n").validRows()).isEqualTo(1);
    }

    @Test
    void directRequestsCannotLosePrecisionInDatabaseColumns() {
        assertThatThrownBy(() -> service.create(new TransactionDtos.TransactionRequest(null, TransactionType.DEPOSIT,
                LocalDate.parse("2026-08-01"), null, null, new BigDecimal("0.0000001"), null, null, null)))
                .isInstanceOf(DomainException.class);
        verify(transactions, never()).saveAndFlush(any());
    }

    @Test
    void directCsvCommitTrimsDatesLikePreviewValidation() {
        when(transactions.save(any(TransactionEntity.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.commit(new TransactionDtos.CsvCommitRequest(UUID.randomUUID(), List.of(
                new TransactionDtos.CsvRowRequest(" 2026-08-01 ", "DEPOSIT", "", null, null, null, "100", null, null))));
        assertThat(result.importedRows()).isEqualTo(1);
    }

    private TransactionDtos.CsvPreviewResponse preview(String text) {
        return service.preview(new MockMultipartFile("file", "ledger.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8)));
    }
}
