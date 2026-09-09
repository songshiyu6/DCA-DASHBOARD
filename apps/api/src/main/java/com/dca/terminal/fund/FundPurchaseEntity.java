package com.dca.terminal.fund;

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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "fund_purchase")
public class FundPurchaseEntity extends PersistedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private InstrumentEntity instrument;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(name = "gross_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossAmount;

    @Column(name = "purchase_fee_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal purchaseFeeRate = BigDecimal.ZERO;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal nav;

    @Column(name = "purchase_fee", nullable = false, precision = 20, scale = 6)
    private BigDecimal purchaseFee;

    @Column(name = "net_subscribed_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal netSubscribedAmount;

    @Column(nullable = false, precision = 28, scale = 8)
    private BigDecimal shares;

    @Column(length = 500)
    private String notes;

    public UUID getId() { return id; }
    public InstrumentEntity getInstrument() { return instrument; }
    public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getPurchaseFeeRate() { return purchaseFeeRate; }
    public void setPurchaseFeeRate(BigDecimal purchaseFeeRate) { this.purchaseFeeRate = purchaseFeeRate; }
    public BigDecimal getNav() { return nav; }
    public void setNav(BigDecimal nav) { this.nav = nav; }
    public BigDecimal getPurchaseFee() { return purchaseFee; }
    public void setPurchaseFee(BigDecimal purchaseFee) { this.purchaseFee = purchaseFee; }
    public BigDecimal getNetSubscribedAmount() { return netSubscribedAmount; }
    public void setNetSubscribedAmount(BigDecimal netSubscribedAmount) { this.netSubscribedAmount = netSubscribedAmount; }
    public BigDecimal getShares() { return shares; }
    public void setShares(BigDecimal shares) { this.shares = shares; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
