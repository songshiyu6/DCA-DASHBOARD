package com.dca.terminal.portfolio;

import com.dca.terminal.common.CreatedEntity;
import com.dca.terminal.common.FreshnessStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "portfolio_daily_settlement")
public class PortfolioDailySettlementEntity extends CreatedEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "settlement_date", nullable = false, unique = true)
    private LocalDate settlementDate;

    @Column(name = "settlement_at", nullable = false)
    private Instant settlementAt;

    @Column(name = "market_value", precision = 20, scale = 6)
    private BigDecimal marketValue;

    @Column(name = "net_cash_flow", nullable = false, precision = 20, scale = 6)
    private BigDecimal netCashFlow;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_status", nullable = false)
    private FreshnessStatus dataStatus;

    public UUID getId() { return id; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
    public Instant getSettlementAt() { return settlementAt; }
    public void setSettlementAt(Instant settlementAt) { this.settlementAt = settlementAt; }
    public BigDecimal getMarketValue() { return marketValue; }
    public void setMarketValue(BigDecimal marketValue) { this.marketValue = marketValue; }
    public BigDecimal getNetCashFlow() { return netCashFlow; }
    public void setNetCashFlow(BigDecimal netCashFlow) { this.netCashFlow = netCashFlow; }
    public FreshnessStatus getDataStatus() { return dataStatus; }
    public void setDataStatus(FreshnessStatus dataStatus) { this.dataStatus = dataStatus; }
}
