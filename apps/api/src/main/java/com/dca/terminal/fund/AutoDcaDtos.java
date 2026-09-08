package com.dca.terminal.fund;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AutoDcaDtos {
    private AutoDcaDtos() { }

    public record RuleRequest(
            @NotBlank @Size(max = 16) String fundCode,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @DecimalMin("0") @DecimalMax("1") BigDecimal purchaseFeeRate,
            Boolean enabled) { }

    public record RuleResponse(
            UUID id,
            UUID instrumentId,
            String fundCode,
            String fundName,
            BigDecimal amount,
            String currency,
            AutoDcaFrequency frequency,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal purchaseFeeRate,
            boolean enabled,
            String editSemantics) { }

    public record DailyExecutionResponse(
            LocalDate orderDate,
            LocalDate navDate,
            LocalDate confirmationDate,
            BigDecimal nav,
            BigDecimal grossAmount,
            BigDecimal purchaseFee,
            BigDecimal netSubscribedAmount,
            BigDecimal shares,
            AutoDcaProjectionEngine.ConfirmationStatus status) { }

    public record SummaryResponse(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            int executionCount,
            int confirmedCount,
            BigDecimal grossAmount,
            BigDecimal purchaseFees,
            BigDecimal netSubscribedAmount,
            BigDecimal shares,
            BigDecimal averageNav,
            BigDecimal averageCostPerShare,
            BigDecimal latestNav,
            BigDecimal currentValue,
            BigDecimal currentPnl,
            BigDecimal returnRate) { }

    public record ProjectionResponse(
            RuleResponse rule,
            AutoDcaProjectionEngine.GroupBy groupBy,
            BigDecimal latestNav,
            LocalDate latestNavDate,
            String tradingDaySource,
            List<SummaryResponse> summaries,
            List<DailyExecutionResponse> daily) { }
}
