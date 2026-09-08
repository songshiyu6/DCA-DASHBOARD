package com.dca.terminal.fund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "fund_market_calendar_day")
public class FundMarketCalendarDayEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "calendar_code", nullable = false, length = 32)
    private String calendarCode;

    @Column(name = "market_date", nullable = false)
    private LocalDate marketDate;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    public UUID getId() { return id; }
    public String getCalendarCode() { return calendarCode; }
    public void setCalendarCode(String calendarCode) { this.calendarCode = calendarCode; }
    public LocalDate getMarketDate() { return marketDate; }
    public void setMarketDate(LocalDate marketDate) { this.marketDate = marketDate; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getRetrievedAt() { return retrievedAt; }
    public void setRetrievedAt(Instant retrievedAt) { this.retrievedAt = retrievedAt; }
}
