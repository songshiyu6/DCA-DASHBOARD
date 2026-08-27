package com.dca.terminal.instrument;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.common.PersistedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "instrument")
public class InstrumentEntity extends PersistedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 16)
    private String symbol;

    @Column(nullable = false)
    private String name;

    @Column(length = 32)
    private String exchange;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false, length = 16)
    private InstrumentType instrumentType = InstrumentType.ETF;

    @Column(length = 255)
    private String issuer;

    @Column(name = "expense_ratio", precision = 12, scale = 8)
    private BigDecimal expenseRatio;

    @Column(precision = 20, scale = 6)
    private BigDecimal aum;

    @Column(name = "dividend_yield", precision = 12, scale = 8)
    private BigDecimal dividendYield;

    @Column(name = "data_provider", length = 32)
    private String dataProvider;

    @Column(nullable = false)
    private boolean tracked = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_status", nullable = false, length = 32)
    private FreshnessStatus dataStatus = FreshnessStatus.INSUFFICIENT_HISTORY;

    public UUID getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public InstrumentType getInstrumentType() { return instrumentType; }
    public void setInstrumentType(InstrumentType instrumentType) { this.instrumentType = instrumentType; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public BigDecimal getExpenseRatio() { return expenseRatio; }
    public void setExpenseRatio(BigDecimal expenseRatio) { this.expenseRatio = expenseRatio; }
    public BigDecimal getAum() { return aum; }
    public void setAum(BigDecimal aum) { this.aum = aum; }
    public BigDecimal getDividendYield() { return dividendYield; }
    public void setDividendYield(BigDecimal dividendYield) { this.dividendYield = dividendYield; }
    public String getDataProvider() { return dataProvider; }
    public void setDataProvider(String dataProvider) { this.dataProvider = dataProvider; }
    public boolean isTracked() { return tracked; }
    public void setTracked(boolean tracked) { this.tracked = tracked; }
    public FreshnessStatus getDataStatus() { return dataStatus; }
    public void setDataStatus(FreshnessStatus dataStatus) { this.dataStatus = dataStatus; }
}
