package com.dca.terminal.transaction;

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
@Table(name = "investment_transaction")
public class TransactionEntity extends PersistedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ledger_order", nullable = false, updatable = false)
    private Long ledgerOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private InstrumentEntity instrument;

    @Column(name = "plan_cycle_id")
    private UUID planCycleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 16)
    private TransactionType transactionType;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 20, scale = 6)
    private BigDecimal unitPrice;

    @Column(precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal fee = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(length = 1_000)
    private String notes;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @Column(name = "import_fingerprint", length = 128)
    private String importFingerprint;

    public UUID getId() { return id; }
    public Long getLedgerOrder() { return ledgerOrder; }
    public void setLedgerOrder(Long ledgerOrder) { this.ledgerOrder = ledgerOrder; }
    public InstrumentEntity getInstrument() { return instrument; }
    public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
    public UUID getPlanCycleId() { return planCycleId; }
    public void setPlanCycleId(UUID planCycleId) { this.planCycleId = planCycleId; }
    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getFee() { return fee == null ? BigDecimal.ZERO : fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public UUID getImportBatchId() { return importBatchId; }
    public void setImportBatchId(UUID importBatchId) { this.importBatchId = importBatchId; }
    public String getImportFingerprint() { return importFingerprint; }
    public void setImportFingerprint(String importFingerprint) { this.importFingerprint = importFingerprint; }
}
