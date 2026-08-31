package com.dca.terminal.plan;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.transaction.ContributionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ContributionDtos {
    private ContributionDtos() { }

    public record UnclassifiedBuy(UUID transactionId, LocalDate tradeDate, String symbol, BigDecimal principal,
                                  boolean eligibleForInitial) { }

    public record ContributionBatchResponse(ContributionType type, String period, BigDecimal principal,
                                            BigDecimal value, BigDecimal pnl, BigDecimal returnRate,
                                            int averageMarketDays, FreshnessStatus dataStatus) { }

    public record ContributionBucketResponse(BigDecimal principal, BigDecimal value, BigDecimal pnl,
                                             BigDecimal returnRate,
                                             int averageMarketDays, int batchCount,
                                             FreshnessStatus dataStatus) { }

    public record ContributionAnalysisResponse(BigDecimal totalInvested,
                                               ContributionBucketResponse initial,
                                               ContributionBucketResponse dca,
                                               BigDecimal unclassifiedAmount,
                                               List<UnclassifiedBuy> unclassifiedBuys,
                                               String unclassifiedScope,
                                               List<ContributionBatchResponse> batches,
                                               FreshnessStatus dataStatus,
                                               LocalDate asOf) { }

    public record ClassificationItemRequest(@NotNull UUID transactionId,
                                            @NotNull ContributionType classification) { }

    public record ClassificationPreviewRequest(
            @NotEmpty List<@Valid ClassificationItemRequest> items) { }

    public record ClassificationPreviewItem(UUID transactionId, LocalDate tradeDate, String symbol,
                                            BigDecimal principal, ContributionType classification,
                                            boolean valid, List<ClassificationError> errors) { }

    public record ClassificationError(String code, String message) { }

    public record ClassificationPreviewResponse(String previewHash, boolean valid,
                                                 List<ClassificationPreviewItem> items) { }

    public record ClassificationCommitRequest(@NotBlank String previewHash,
                                               @NotEmpty List<@Valid ClassificationItemRequest> items) { }

    public record ClassificationCommitResponse(UUID batchId, List<UUID> transactionIds,
                                                ContributionAnalysisResponse analysis) { }

    public record ClassificationAuditResponse(UUID id, UUID batchId, UUID planId, UUID transactionId,
                                              ContributionType previousType, UUID previousPlanId,
                                              ContributionType newType, UUID newPlanId, Instant createdAt) { }
}
