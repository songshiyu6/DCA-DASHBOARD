package com.dca.terminal.fund;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FundDtos {
    private FundDtos() { }

    public record FundRequest(
            @NotBlank @Size(max = 16) String code,
            @NotBlank @Size(max = 255) String name,
            @DecimalMin("0") @DecimalMax("1") BigDecimal managementFeeRate,
            @Min(0) @Max(10) Integer confirmationTradingDays,
            @Size(max = 16) String shareClass) { }

    public record FundResponse(
            UUID id,
            String code,
            String name,
            String currency,
            BigDecimal managementFeeRate,
            int confirmationTradingDays,
            String shareClass,
            String calendarCode) { }

    public record NavRequest(
            @NotNull LocalDate navDate,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal nav,
            @Size(max = 32) String source) { }

    public record NavResponse(
            UUID id,
            LocalDate navDate,
            BigDecimal nav,
            String source,
            Instant retrievedAt) { }

    public record FundSyncResponse(
            UUID fundId,
            String fundCode,
            String source,
            LocalDate startDate,
            LocalDate endDate,
            int navPointsReceived,
            int tradingDaysReceived,
            int missingNavCount,
            List<LocalDate> missingNavDates,
            LocalDate latestNavDate,
            Instant completedAt) { }

    public record FundCalendarResponse(
            String calendarCode,
            LocalDate startDate,
            LocalDate endDate,
            String source,
            boolean calendarAvailable,
            int expectedTradingDays,
            int navDays,
            List<LocalDate> missingNavDates) { }
}
