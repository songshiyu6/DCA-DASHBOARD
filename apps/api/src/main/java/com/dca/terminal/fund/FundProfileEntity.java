package com.dca.terminal.fund;

import com.dca.terminal.common.PersistedEntity;
import com.dca.terminal.instrument.InstrumentEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fund_profile")
public class FundProfileEntity extends PersistedEntity {
    @Id
    @Column(name = "instrument_id")
    private UUID instrumentId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private InstrumentEntity instrument;

    @Column(name = "management_fee_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal managementFeeRate = BigDecimal.ZERO;

    @Column(name = "confirmation_trading_days", nullable = false)
    private int confirmationTradingDays = 1;

    @Column(name = "share_class", length = 16)
    private String shareClass;

    @Column(name = "calendar_code", nullable = false, length = 32)
    private String calendarCode = "CN_FUND";

    public UUID getInstrumentId() { return instrumentId; }
    public InstrumentEntity getInstrument() { return instrument; }
    public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
    public BigDecimal getManagementFeeRate() { return managementFeeRate; }
    public void setManagementFeeRate(BigDecimal managementFeeRate) { this.managementFeeRate = managementFeeRate; }
    public int getConfirmationTradingDays() { return confirmationTradingDays; }
    public void setConfirmationTradingDays(int confirmationTradingDays) { this.confirmationTradingDays = confirmationTradingDays; }
    public String getShareClass() { return shareClass; }
    public void setShareClass(String shareClass) { this.shareClass = shareClass; }
    public String getCalendarCode() { return calendarCode; }
    public void setCalendarCode(String calendarCode) { this.calendarCode = calendarCode; }
}
