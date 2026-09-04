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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "portfolio_snapshot_daily")
public class PortfolioSnapshotEntity extends CreatedEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "snapshot_date", nullable = false, unique = true)
    private LocalDate snapshotDate;
    @Column(name = "market_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal marketValue;
    @Column(name = "securities_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal securitiesValue;
    @Column(name = "cash_balance", nullable = false, precision = 20, scale = 6)
    private BigDecimal cashBalance;
    @Column(name = "cost_basis", nullable = false, precision = 20, scale = 6)
    private BigDecimal costBasis;
    @Column(name = "net_cash_flow", nullable = false, precision = 20, scale = 6)
    private BigDecimal netCashFlow;
    @Column(name = "realized_pl", nullable = false, precision = 20, scale = 6)
    private BigDecimal realizedPnl;
    @Column(name = "unrealized_pl", precision = 20, scale = 6)
    private BigDecimal unrealizedPnl;
    @Column(name = "dividend_income", nullable = false, precision = 20, scale = 6)
    private BigDecimal dividendIncome;
    @Column(name = "total_fees", nullable = false, precision = 20, scale = 6)
    private BigDecimal totalFees;
    @Enumerated(EnumType.STRING)
    @Column(name = "data_status", nullable = false)
    private FreshnessStatus dataStatus;

    public UUID getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
    public BigDecimal getMarketValue() { return marketValue; }
    public void setMarketValue(BigDecimal marketValue) { this.marketValue = marketValue; }
    public BigDecimal getSecuritiesValue() { return securitiesValue; }
    public void setSecuritiesValue(BigDecimal securitiesValue) { this.securitiesValue = securitiesValue; }
    public BigDecimal getCashBalance() { return cashBalance; }
    public void setCashBalance(BigDecimal cashBalance) { this.cashBalance = cashBalance; }
    public BigDecimal getCostBasis() { return costBasis; }
    public void setCostBasis(BigDecimal costBasis) { this.costBasis = costBasis; }
    public BigDecimal getNetCashFlow() { return netCashFlow; }
    public void setNetCashFlow(BigDecimal netCashFlow) { this.netCashFlow = netCashFlow; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public void setUnrealizedPnl(BigDecimal unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; }
    public BigDecimal getDividendIncome() { return dividendIncome; }
    public void setDividendIncome(BigDecimal dividendIncome) { this.dividendIncome = dividendIncome; }
    public BigDecimal getTotalFees() { return totalFees; }
    public void setTotalFees(BigDecimal totalFees) { this.totalFees = totalFees; }
    public FreshnessStatus getDataStatus() { return dataStatus; }
    public void setDataStatus(FreshnessStatus dataStatus) { this.dataStatus = dataStatus; }
}
