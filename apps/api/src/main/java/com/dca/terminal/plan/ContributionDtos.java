package com.dca.terminal.plan;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.transaction.ContributionType;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ContributionDtos {
    private ContributionDtos() { }

    public record InitialCapitalRequest(@DecimalMin("0") BigDecimal amount) { }

    public record UnclassifiedBuy(UUID transactionId, LocalDate tradeDate, String symbol, BigDecimal principal) { }

    public record ContributionBatchResponse(ContributionType type, String period, BigDecimal principal,
                                            BigDecimal value, BigDecimal pnl, BigDecimal returnRate,
                                            int averageMarketDays, FreshnessStatus dataStatus) { }

    public record ContributionBucketResponse(BigDecimal plannedPrincipal, BigDecimal principal,
                                             BigDecimal value, BigDecimal pnl, BigDecimal returnRate,
                                             int averageMarketDays, int batchCount,
                                             FreshnessStatus dataStatus) { }

    public record ContributionAnalysisResponse(BigDecimal totalInvested,
                                               ContributionBucketResponse initial,
                                               ContributionBucketResponse dca,
                                               BigDecimal unclassifiedAmount,
                                               List<UnclassifiedBuy> unclassifiedBuys,
                                               List<ContributionBatchResponse> batches,
                                               FreshnessStatus dataStatus,
                                               LocalDate asOf) { }
}
