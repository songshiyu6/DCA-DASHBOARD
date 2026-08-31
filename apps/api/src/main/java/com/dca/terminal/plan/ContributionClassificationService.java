package com.dca.terminal.plan;

import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.transaction.ContributionType;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.plan.ContributionDtos.ClassificationAuditResponse;
import static com.dca.terminal.plan.ContributionDtos.ClassificationCommitRequest;
import static com.dca.terminal.plan.ContributionDtos.ClassificationError;
import static com.dca.terminal.plan.ContributionDtos.ClassificationItemRequest;
import static com.dca.terminal.plan.ContributionDtos.ClassificationPreviewItem;
import static com.dca.terminal.plan.ContributionDtos.ClassificationPreviewRequest;
import static com.dca.terminal.plan.ContributionDtos.ClassificationPreviewResponse;

@Service
public class ContributionClassificationService {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private final PlanRepository planRepository;
    private final TransactionRepository transactionRepository;
    private final ContributionClassificationAuditRepository auditRepository;
    private final Clock clock;

    public ContributionClassificationService(PlanRepository planRepository,
                                             TransactionRepository transactionRepository,
                                             ContributionClassificationAuditRepository auditRepository,
                                             Clock clock) {
        this.planRepository = planRepository;
        this.transactionRepository = transactionRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ClassificationPreviewResponse preview(UUID planId, ClassificationPreviewRequest request) {
        InvestmentPlanEntity plan = getPlan(planId);
        List<ClassificationItemRequest> items = request == null ? null : request.items();
        if (items == null || items.isEmpty()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CONTRIBUTION_CLASSIFICATION_EMPTY",
                    "Select at least one transaction to classify");
        }
        Map<UUID, TransactionEntity> transactions = transactionRepository.findAllById(
                        items.stream().map(ClassificationItemRequest::transactionId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(java.util.stream.Collectors.toMap(TransactionEntity::getId, value -> value));
        return buildPreview(plan, items, transactions);
    }

    @Transactional
    public CommitResult commit(UUID planId, ClassificationCommitRequest request) {
        InvestmentPlanEntity plan = getPlan(planId);
        List<ClassificationItemRequest> items = request == null ? null : request.items();
        if (items == null || items.isEmpty()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CONTRIBUTION_CLASSIFICATION_EMPTY",
                    "Select at least one transaction to classify");
        }
        List<UUID> requestedIds = items.stream().map(ClassificationItemRequest::transactionId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, TransactionEntity> transactions = transactionRepository.findAllByIdInForUpdate(requestedIds)
                .stream().collect(java.util.stream.Collectors.toMap(TransactionEntity::getId, value -> value));
        ClassificationPreviewResponse current = buildPreview(plan, items, transactions);
        if (!current.valid()) {
            throw new DomainException(HttpStatus.CONFLICT, "CONTRIBUTION_CLASSIFICATION_INVALID",
                    "One or more selected transactions can no longer be classified");
        }
        if (request.previewHash() == null || !request.previewHash().equals(current.previewHash())) {
            throw new DomainException(HttpStatus.CONFLICT, "CONTRIBUTION_PREVIEW_STALE",
                    "Contribution classification preview is stale; preview the selection again");
        }

        UUID batchId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        List<ContributionClassificationAuditEntity> auditRows = new ArrayList<>();
        List<UUID> affected = new ArrayList<>();
        for (ClassificationItemRequest item : items) {
            TransactionEntity transaction = transactions.get(item.transactionId());
            ContributionClassificationAuditEntity audit = new ContributionClassificationAuditEntity();
            audit.setBatchId(batchId);
            audit.setPlanId(planId);
            audit.setTransactionId(transaction.getId());
            audit.setPreviousType(transaction.getContributionType());
            audit.setPreviousPlanId(transaction.getContributionPlanId());
            audit.setNewType(item.classification());
            audit.setNewPlanId(item.classification() == ContributionType.INITIAL ? planId : null);
            audit.setCreatedAt(now);
            auditRows.add(audit);

            transaction.setContributionType(item.classification());
            transaction.setContributionPlanId(item.classification() == ContributionType.INITIAL ? planId : null);
            affected.add(transaction.getId());
        }
        transactionRepository.saveAllAndFlush(transactions.values());
        auditRepository.saveAllAndFlush(auditRows);
        return new CommitResult(batchId, List.copyOf(affected));
    }

    @Transactional(readOnly = true)
    public List<ClassificationAuditResponse> audit(UUID planId) {
        getPlan(planId);
        return auditRepository.findTop100ByPlanIdOrderByCreatedAtDescIdDesc(planId).stream()
                .map(row -> new ClassificationAuditResponse(row.getId(), row.getBatchId(), row.getPlanId(),
                        row.getTransactionId(), row.getPreviousType(), row.getPreviousPlanId(), row.getNewType(),
                        row.getNewPlanId(), row.getCreatedAt()))
                .toList();
    }

    private ClassificationPreviewResponse buildPreview(InvestmentPlanEntity plan,
                                                        List<ClassificationItemRequest> requests,
                                                        Map<UUID, TransactionEntity> transactions) {
        Map<UUID, Integer> counts = new HashMap<>();
        requests.stream().map(ClassificationItemRequest::transactionId).filter(java.util.Objects::nonNull)
                .forEach(id -> counts.merge(id, 1, Integer::sum));
        List<ClassificationPreviewItem> previewItems = new ArrayList<>();
        for (ClassificationItemRequest request : requests) {
            TransactionEntity transaction = request.transactionId() == null ? null : transactions.get(request.transactionId());
            List<ClassificationError> errors = validate(plan, request, transaction,
                    request.transactionId() != null && counts.getOrDefault(request.transactionId(), 0) > 1);
            previewItems.add(new ClassificationPreviewItem(request.transactionId(),
                    transaction == null ? null : transaction.getTradeDate(),
                    transaction == null || transaction.getInstrument() == null ? null : transaction.getInstrument().getSymbol(),
                    transaction == null ? null : DecimalMath.money(buyPrincipal(transaction)),
                    request.classification(), errors.isEmpty(), List.copyOf(errors)));
        }
        boolean valid = previewItems.stream().allMatch(ClassificationPreviewItem::valid);
        return new ClassificationPreviewResponse(valid ? previewHash(plan.getId(), requests, transactions) : null,
                valid, List.copyOf(previewItems));
    }

    private List<ClassificationError> validate(InvestmentPlanEntity plan, ClassificationItemRequest request,
                                               TransactionEntity transaction, boolean duplicate) {
        List<ClassificationError> errors = new ArrayList<>();
        if (request.transactionId() == null) {
            errors.add(error("TRANSACTION_ID_REQUIRED", "Transaction ID is required"));
            return errors;
        }
        if (duplicate) errors.add(error("DUPLICATE_TRANSACTION", "Transaction is selected more than once"));
        if (transaction == null) {
            errors.add(error("TRANSACTION_NOT_FOUND", "Transaction not found"));
            return errors;
        }
        if (request.classification() != ContributionType.INITIAL
                && request.classification() != ContributionType.UNPLANNED) {
            errors.add(error("UNSUPPORTED_CONTRIBUTION_CLASSIFICATION",
                    "Legacy BUYs can only be classified as INITIAL or UNPLANNED"));
        }
        if (transaction.getTransactionType() != TransactionType.BUY) {
            errors.add(error("CONTRIBUTION_SOURCE_REQUIRES_BUY", "Only BUY transactions can be classified"));
        }
        if (transaction.getPlanCycleId() != null) {
            errors.add(error("DCA_CONTRIBUTION_ALREADY_CLASSIFIED",
                    "A BUY linked to a DCA cycle cannot be reclassified"));
        }
        if (transaction.getContributionType() != null || transaction.getContributionPlanId() != null) {
            errors.add(error("CONTRIBUTION_ALREADY_CLASSIFIED", "Transaction already has a contribution source"));
        }
        if (request.classification() == ContributionType.INITIAL
                && !plan.getStartDate().equals(transaction.getTradeDate())) {
            errors.add(error("INITIAL_CONTRIBUTION_START_DATE_ONLY",
                    "Initial capital is only allowed on the investment plan start date"));
        }
        return errors;
    }

    private String previewHash(UUID planId, List<ClassificationItemRequest> requests,
                               Map<UUID, TransactionEntity> transactions) {
        List<ClassificationItemRequest> ordered = requests.stream()
                .sorted(Comparator.comparing((ClassificationItemRequest item) -> item.transactionId().toString())
                        .thenComparing(item -> item.classification().name()))
                .toList();
        StringBuilder source = new StringBuilder(planId.toString());
        for (ClassificationItemRequest request : ordered) {
            TransactionEntity transaction = transactions.get(request.transactionId());
            source.append('|').append(request.transactionId()).append(':').append(request.classification())
                    .append(':').append(transaction.getUpdatedAt())
                    .append(':').append(transaction.getTransactionType())
                    .append(':').append(transaction.getTradeDate())
                    .append(':').append(transaction.getPlanCycleId())
                    .append(':').append(transaction.getContributionType())
                    .append(':').append(transaction.getContributionPlanId())
                    .append(':').append(transaction.getQuantity())
                    .append(':').append(transaction.getUnitPrice())
                    .append(':').append(transaction.getFee())
                    .append(':').append(transaction.getInstrument().getId());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BigDecimal buyPrincipal(TransactionEntity transaction) {
        if (transaction.getQuantity() == null || transaction.getUnitPrice() == null) return null;
        return transaction.getQuantity().multiply(transaction.getUnitPrice(), MC)
                .add(DecimalMath.zeroIfNull(transaction.getFee()), MC);
    }

    private InvestmentPlanEntity getPlan(UUID planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "Investment plan not found"));
    }

    private ClassificationError error(String code, String message) {
        return new ClassificationError(code, message);
    }

    public record CommitResult(UUID batchId, List<UUID> transactionIds) { }
}
