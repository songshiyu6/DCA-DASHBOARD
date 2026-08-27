package com.dca.terminal.plan;

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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "investment_plan")
public class InvestmentPlanEntity extends PersistedEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, length = 3)
    private String currency = "USD";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanFrequency frequency = PlanFrequency.MONTHLY;
    @Column(name = "monthly_budget", nullable = false, precision = 20, scale = 6)
    private BigDecimal monthlyBudget;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "execution_start_day", nullable = false)
    private int executionStartDay = 1;
    @Column(name = "execution_end_day", nullable = false)
    private int executionEndDay = 7;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanStatus status = PlanStatus.ACTIVE;

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PlanFrequency getFrequency() { return frequency; }
    public void setFrequency(PlanFrequency frequency) { this.frequency = frequency; }
    public BigDecimal getMonthlyBudget() { return monthlyBudget; }
    public void setMonthlyBudget(BigDecimal monthlyBudget) { this.monthlyBudget = monthlyBudget; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public int getExecutionStartDay() { return executionStartDay; }
    public void setExecutionStartDay(int executionStartDay) { this.executionStartDay = executionStartDay; }
    public int getExecutionEndDay() { return executionEndDay; }
    public void setExecutionEndDay(int executionEndDay) { this.executionEndDay = executionEndDay; }
    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }
}
