package com.dca.terminal.transaction;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransactionDtos {
    private TransactionDtos() { }

    public record TransactionRequest(
            @JsonAlias("symbol") @NotBlank String instrumentSymbol,
            @JsonAlias("type") @NotNull TransactionType transactionType,
            @NotNull LocalDate tradeDate,
            @DecimalMin(value = "0.00000001", inclusive = true) BigDecimal quantity,
            @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
            @DecimalMin(value = "0", inclusive = true) BigDecimal amount,
            @DecimalMin(value = "0", inclusive = true) BigDecimal fee,
            UUID planCycleId,
            @Size(max = 1000) String notes) {
        public String symbol() { return instrumentSymbol; }
        public TransactionType type() { return transactionType; }
    }

    public record TransactionResponse(
            UUID id, String instrumentSymbol, String instrumentName, TransactionType transactionType, LocalDate tradeDate,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, BigDecimal fee,
            String currency, UUID planCycleId, String notes, Instant createdAt, Instant updatedAt, Long ledgerOrder) { }

    public record CsvRowRequest(
            @NotBlank String date, @NotBlank String type, @NotBlank String symbol,
            String quantity, String price, String fee, String amount,
            @JsonAlias("plan_cycle_id") String planCycleId, String notes) { }

    public record CsvRowPreview(int rowNumber, CsvRowRequest row, boolean valid, List<String> errors,
                                String fingerprint) { }

    public record CsvPreviewResponse(UUID batchId, int totalRows, int validRows, int invalidRows,
                                     List<CsvRowPreview> rows) { }

    public record CsvCommitRequest(@NotNull UUID batchId, @NotNull List<CsvRowRequest> rows) { }

    public record CsvCommitResponse(UUID batchId, int importedRows, List<UUID> transactionIds) { }
}
