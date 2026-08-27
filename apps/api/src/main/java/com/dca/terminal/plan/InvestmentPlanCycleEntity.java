package com.dca.terminal.plan;

import com.dca.terminal.common.PersistedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "investment_plan_cycle")
public class InvestmentPlanCycleEntity extends PersistedEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private InvestmentPlanEntity plan;
    @Column(nullable = false, length = 7)
    private String period;
    @Column(name = "planned_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal plannedAmount;
    @Column(name = "executed_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal executedAmount = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CycleStatus status = CycleStatus.UPCOMING;
    @Column(name = "opened_at")
    private Instant openedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getId() { return id; }
    public InvestmentPlanEntity getPlan() { return plan; }
    public void setPlan(InvestmentPlanEntity plan) { this.plan = plan; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BigDecimal getPlannedAmount() { return plannedAmount; }
    public void setPlannedAmount(BigDecimal plannedAmount) { this.plannedAmount = plannedAmount; }
    public BigDecimal getExecutedAmount() { return executedAmount; }
    public void setExecutedAmount(BigDecimal executedAmount) { this.executedAmount = executedAmount; }
    public CycleStatus getStatus() { return status; }
    public void setStatus(CycleStatus status) { this.status = status; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
