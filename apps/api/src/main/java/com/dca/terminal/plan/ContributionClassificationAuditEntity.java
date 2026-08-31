package com.dca.terminal.plan;

import com.dca.terminal.transaction.ContributionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contribution_classification_audit")
public class ContributionClassificationAuditEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_type", length = 16)
    private ContributionType previousType;

    @Column(name = "previous_plan_id")
    private UUID previousPlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_type", nullable = false, length = 16)
    private ContributionType newType;

    @Column(name = "new_plan_id")
    private UUID newPlanId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public ContributionType getPreviousType() { return previousType; }
    public void setPreviousType(ContributionType previousType) { this.previousType = previousType; }
    public UUID getPreviousPlanId() { return previousPlanId; }
    public void setPreviousPlanId(UUID previousPlanId) { this.previousPlanId = previousPlanId; }
    public ContributionType getNewType() { return newType; }
    public void setNewType(ContributionType newType) { this.newType = newType; }
    public UUID getNewPlanId() { return newPlanId; }
    public void setNewPlanId(UUID newPlanId) { this.newPlanId = newPlanId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
