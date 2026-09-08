package com.dca.terminal.fund;

import com.dca.terminal.common.PersistedEntity;
import com.dca.terminal.instrument.InstrumentEntity;
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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "auto_dca_rule")
public class AutoDcaRuleEntity extends PersistedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private InstrumentEntity instrument;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "CNY";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AutoDcaFrequency frequency = AutoDcaFrequency.DAILY_FUND_TRADING_DAY;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "purchase_fee_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal purchaseFeeRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean enabled = true;

    public UUID getId() { return id; }
    public InstrumentEntity getInstrument() { return instrument; }
    public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public AutoDcaFrequency getFrequency() { return frequency; }
    public void setFrequency(AutoDcaFrequency frequency) { this.frequency = frequency; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public BigDecimal getPurchaseFeeRate() { return purchaseFeeRate; }
    public void setPurchaseFeeRate(BigDecimal purchaseFeeRate) { this.purchaseFeeRate = purchaseFeeRate; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
