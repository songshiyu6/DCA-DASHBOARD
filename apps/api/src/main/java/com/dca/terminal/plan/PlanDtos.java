package com.dca.terminal.plan;

import com.dca.terminal.common.FreshnessStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PlanDtos {
    private PlanDtos() { }

    public record PlanAssetRequest(@NotBlank String symbol, @NotNull @DecimalMin("0.00000001") BigDecimal targetWeight) { }

    public record PlanRequest(@NotBlank String name, PlanFrequency frequency,
                              @NotNull @DecimalMin("0.01") BigDecimal monthlyBudget,
                              @NotNull LocalDate startDate, Integer executionStartDay, Integer executionEndDay,
                              PlanStatus status, @NotEmpty List<@Valid PlanAssetRequest> assets) { }

    public record PlanAssetResponse(String symbol, String name, BigDecimal targetWeight,
                                     BigDecimal plannedAmount) { }

    public record PlanResponse(UUID id, String name, String currency, PlanFrequency frequency,
                               BigDecimal monthlyBudget, LocalDate startDate, int executionStartDay,
                               int executionEndDay, PlanStatus status, List<PlanAssetResponse> assets,
                               Instant createdAt, Instant updatedAt) { }

    public record CycleAssetResponse(String symbol, BigDecimal targetWeight, BigDecimal plannedAmount,
                                     BigDecimal executedAmount) { }

    public record CycleResponse(UUID id, UUID planId, String period, BigDecimal plannedAmount,
                                BigDecimal executedAmount, CycleStatus status,
                                List<CycleAssetResponse> assets, Instant openedAt, Instant completedAt) { }

    public record RecommendationItem(String symbol, BigDecimal currentWeight, BigDecimal targetWeight,
                                     BigDecimal currentValue, BigDecimal gap, BigDecimal suggestedAmount,
                                     BigDecimal positiveGap, String reason, BigDecimal valueGap) {
        public RecommendationItem(String symbol, BigDecimal currentWeight, BigDecimal targetWeight,
                                   BigDecimal currentValue, BigDecimal gap, BigDecimal suggestedAmount) {
            this(symbol, currentWeight, targetWeight, currentValue, gap, suggestedAmount,
                    gap == null ? null : gap.max(BigDecimal.ZERO), reasonFor(gap), gap);
        }

        private static String reasonFor(BigDecimal gap) {
            if (gap == null) return "PRICE_UNAVAILABLE";
            return gap.signum() < 0 ? "OVERWEIGHT" : gap.signum() > 0 ? "UNDERWEIGHT" : "AT_TARGET";
        }
    }

    public record RecommendationResponse(BigDecimal amount,
                                         @JsonProperty("dataStatus") FreshnessStatus status,
                                         List<RecommendationItem> items, String message) {
        public RecommendationResponse(BigDecimal amount, FreshnessStatus status, List<RecommendationItem> items) {
            this(amount, status, items, null);
        }
    }

    public record ContributionMonth(String period, BigDecimal planned, BigDecimal executed, CycleStatus status) { }

    public record ContributionProgress(int year, BigDecimal executed, BigDecimal planned, BigDecimal remaining,
                                       BigDecimal executionRate, List<ContributionMonth> months) { }

    public record NextDcaResponse(String period, BigDecimal amount, int daysRemaining,
                                  List<RecommendationItem> items,
                                  @JsonProperty("dataStatus") FreshnessStatus status, String message) {
        public NextDcaResponse(String period, BigDecimal amount, int daysRemaining, List<RecommendationItem> items) {
            this(period, amount, daysRemaining, items, FreshnessStatus.FRESH, null);
        }
    }
}
