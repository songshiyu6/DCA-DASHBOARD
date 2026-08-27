package com.dca.terminal.plan;

import com.dca.terminal.common.PersistedEntity;
import com.dca.terminal.instrument.InstrumentEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "investment_plan_asset")
public class InvestmentPlanAssetEntity extends PersistedEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private InvestmentPlanEntity plan;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private InstrumentEntity instrument;
    @Column(name = "target_weight", nullable = false, precision = 12, scale = 8)
    private BigDecimal targetWeight;

    public UUID getId() { return id; }
    public InvestmentPlanEntity getPlan() { return plan; }
    public void setPlan(InvestmentPlanEntity plan) { this.plan = plan; }
    public InstrumentEntity getInstrument() { return instrument; }
    public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
    public BigDecimal getTargetWeight() { return targetWeight; }
    public void setTargetWeight(BigDecimal targetWeight) { this.targetWeight = targetWeight; }
}
