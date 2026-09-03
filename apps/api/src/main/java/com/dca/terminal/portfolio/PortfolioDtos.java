package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.plan.PlanDtos.ContributionProgress;
import com.dca.terminal.plan.PlanDtos.NextDcaResponse;
import com.dca.terminal.plan.PlanDtos.RecommendationResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PortfolioDtos {
    private PortfolioDtos() { }

    public record SummaryResponse(BigDecimal marketValue, BigDecimal costBasis, BigDecimal netInvested,
                                  BigDecimal unrealizedPnl, BigDecimal realizedPnl, BigDecimal dividendIncome,
                                  BigDecimal totalFees, BigDecimal totalPnl, BigDecimal xirr,
                                  @com.fasterxml.jackson.annotation.JsonProperty("dataStatus") FreshnessStatus status,
                                  Instant asOf) { }

    public record HoldingResponse(String symbol, String name, BigDecimal price, BigDecimal todayPercent,
                                  BigDecimal shares, BigDecimal avgCost, BigDecimal costBasis,
                                  BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal returnPercent,
                                  BigDecimal allocation,
                                  FreshnessStatus dataStatus) { }

    public record HistoryPoint(LocalDate date, BigDecimal marketValue, BigDecimal netInvested,
                               BigDecimal costBasis, BigDecimal unrealizedPnl, FreshnessStatus status) { }

    public record AllocationResponse(String symbol, BigDecimal targetWeight, BigDecimal actualWeight,
                                     BigDecimal drift, BigDecimal marketValue) { }

    public record CurrentValue(UUIDValue instrument, BigDecimal marketValue, BigDecimal price,
                               FreshnessStatus status) { }

    public record UUIDValue(java.util.UUID value) { }

    public record DashboardResponse(SummaryResponse summary, NextDcaResponse nextDca,
                                    List<HistoryPoint> portfolioHistory, List<HoldingResponse> holdings,
                                    List<AllocationResponse> allocation,
                                    ContributionProgress contributionProgress) { }
}