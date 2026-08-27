package com.dca.terminal.plan;

import com.dca.terminal.instrument.InstrumentEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "investment_plan_cycle_asset")
public class InvestmentPlanCycleAssetEntity {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private InvestmentPlanCycleEntity cycle;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private InstrumentEntity instrument;
    @Column(name = "target_weight", nullable = false, precision = 12, scale = 8)
    private BigDecimal targetWeight;
    @Column(name = "planned_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal plannedAmount;
    @Column(name = "executed_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal executedAmount = BigDecimal.ZERO;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public InvestmentPlanCycleEntity getCycle() { return cycle; }
    public void setCycle(InvestmentPlanCycleEntity cycle) { this.cycle = cycle; }
    public InstrumentEntity getInstrument() { return instrument; }
    public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
    public BigDecimal getTargetWeight() { return targetWeight; }
    public void setTargetWeight(BigDecimal targetWeight) { this.targetWeight = targetWeight; }
    public BigDecimal getPlannedAmount() { return plannedAmount; }
    public void setPlannedAmount(BigDecimal plannedAmount) { this.plannedAmount = plannedAmount; }
    public BigDecimal getExecutedAmount() { return executedAmount; }
    public void setExecutedAmount(BigDecimal executedAmount) { this.executedAmount = executedAmount; }
}
