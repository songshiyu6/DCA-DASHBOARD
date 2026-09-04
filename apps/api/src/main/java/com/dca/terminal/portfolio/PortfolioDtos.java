package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.plan.PlanDtos.ContributionProgress;
import com.dca.terminal.plan.PlanDtos.NextDcaResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PortfolioDtos {
    private PortfolioDtos() { }

    /**
     * marketValue is the total account value (securitiesValue + cashBalance).
     * securitiesValue and cashBalance are exposed separately so consumers never need to infer cash.
     */
    public record SummaryResponse(BigDecimal marketValue, BigDecimal costBasis, BigDecimal netInvested,
                                  BigDecimal unrealizedPnl, BigDecimal realizedPnl, BigDecimal dividendIncome,
                                  BigDecimal totalFees, BigDecimal totalPnl, BigDecimal xirr,
                                  @com.fasterxml.jackson.annotation.JsonProperty("dataStatus") FreshnessStatus status,
                                  Instant asOf, BigDecimal securitiesValue, BigDecimal cashBalance,
                                  BigDecimal interestIncome) {
        public SummaryResponse(BigDecimal marketValue, BigDecimal costBasis, BigDecimal netInvested,
                               BigDecimal unrealizedPnl, BigDecimal realizedPnl, BigDecimal dividendIncome,
                               BigDecimal totalFees, BigDecimal totalPnl, BigDecimal xirr,
                               FreshnessStatus status, Instant asOf) {
            this(marketValue, costBasis, netInvested, unrealizedPnl, realizedPnl, dividendIncome,
                    totalFees, totalPnl, xirr, status, asOf, marketValue, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record HoldingResponse(String symbol, String name, BigDecimal price, BigDecimal todayPercent,
                                  BigDecimal shares, BigDecimal avgCost, BigDecimal costBasis,
                                  BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal returnPercent,
                                  BigDecimal allocation,
                                  FreshnessStatus dataStatus) { }

    /** marketValue is total portfolio value for the date; unrealizedPnl remains securities-only. */
    public record HistoryPoint(LocalDate date, BigDecimal marketValue, BigDecimal netInvested,
                               BigDecimal costBasis, BigDecimal unrealizedPnl, FreshnessStatus status,
                               BigDecimal securitiesValue, BigDecimal cashBalance) {
        public HistoryPoint(LocalDate date, BigDecimal marketValue, BigDecimal netInvested,
                            BigDecimal costBasis, BigDecimal unrealizedPnl, FreshnessStatus status) {
            this(date, marketValue, netInvested, costBasis, unrealizedPnl, status,
                    marketValue, BigDecimal.ZERO);
        }
    }

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
