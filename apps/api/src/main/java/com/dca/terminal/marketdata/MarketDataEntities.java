package com.dca.terminal.marketdata;

import com.dca.terminal.common.CreatedEntity;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class MarketDataEntities {
    private MarketDataEntities() { }

    @Entity
    @Table(name = "market_price_daily")
    public static class PriceDailyEntity extends CreatedEntity {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "instrument_id", nullable = false)
        private InstrumentEntity instrument;
        @Column(name = "trade_date", nullable = false)
        private LocalDate tradeDate;
        @Column(precision = 20, scale = 6)
        private BigDecimal open;
        @Column(precision = 20, scale = 6)
        private BigDecimal high;
        @Column(precision = 20, scale = 6)
        private BigDecimal low;
        @Column(nullable = false, precision = 20, scale = 6)
        private BigDecimal close;
        @Column(name = "adjusted_close", precision = 20, scale = 6)
        private BigDecimal adjustedClose;
        private Long volume;
        @Column(nullable = false, length = 32)
        private String source;

        public UUID getId() { return id; }
        public InstrumentEntity getInstrument() { return instrument; }
        public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
        public LocalDate getTradeDate() { return tradeDate; }
        public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
        public BigDecimal getOpen() { return open; }
        public void setOpen(BigDecimal open) { this.open = open; }
        public BigDecimal getHigh() { return high; }
        public void setHigh(BigDecimal high) { this.high = high; }
        public BigDecimal getLow() { return low; }
        public void setLow(BigDecimal low) { this.low = low; }
        public BigDecimal getClose() { return close; }
        public void setClose(BigDecimal close) { this.close = close; }
        public BigDecimal getAdjustedClose() { return adjustedClose; }
        public void setAdjustedClose(BigDecimal adjustedClose) { this.adjustedClose = adjustedClose; }
        public Long getVolume() { return volume; }
        public void setVolume(Long volume) { this.volume = volume; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    @Entity
    @Table(name = "market_quote_latest")
    public static class QuoteLatestEntity {
        @Id
        @Column(name = "instrument_id")
        private UUID instrumentId;
        @Column(precision = 20, scale = 6)
        private BigDecimal price;
        @Column(name = "previous_close", precision = 20, scale = 6)
        private BigDecimal previousClose;
        @Column(precision = 20, scale = 6)
        private BigDecimal change;
        @Column(name = "change_percent", precision = 12, scale = 8)
        private BigDecimal changePercent;
        @Column(precision = 20, scale = 6)
        private BigDecimal bid;
        @Column(precision = 20, scale = 6)
        private BigDecimal ask;
        @Column(name = "market_timestamp")
        private Instant marketTimestamp;
        @Column(name = "retrieved_at", nullable = false)
        private Instant retrievedAt;
        @Column(length = 32)
        private String source;
        @Enumerated(EnumType.STRING)
        @Column(name = "quote_session", nullable = false, length = 32)
        private QuoteSession quoteSession = QuoteSession.UNKNOWN;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private com.dca.terminal.common.FreshnessStatus status;

        public UUID getInstrumentId() { return instrumentId; }
        public void setInstrumentId(UUID instrumentId) { this.instrumentId = instrumentId; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public BigDecimal getPreviousClose() { return previousClose; }
        public void setPreviousClose(BigDecimal previousClose) { this.previousClose = previousClose; }
        public BigDecimal getChange() { return change; }
        public void setChange(BigDecimal change) { this.change = change; }
        public BigDecimal getChangePercent() { return changePercent; }
        public void setChangePercent(BigDecimal changePercent) { this.changePercent = changePercent; }
        public BigDecimal getBid() { return bid; }
        public void setBid(BigDecimal bid) { this.bid = bid; }
        public BigDecimal getAsk() { return ask; }
        public void setAsk(BigDecimal ask) { this.ask = ask; }
        public Instant getMarketTimestamp() { return marketTimestamp; }
        public void setMarketTimestamp(Instant marketTimestamp) { this.marketTimestamp = marketTimestamp; }
        public Instant getRetrievedAt() { return retrievedAt; }
        public void setRetrievedAt(Instant retrievedAt) { this.retrievedAt = retrievedAt; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public QuoteSession getQuoteSession() { return quoteSession; }
        public void setQuoteSession(QuoteSession quoteSession) {
            this.quoteSession = quoteSession == null ? QuoteSession.UNKNOWN : quoteSession;
        }
        public com.dca.terminal.common.FreshnessStatus getStatus() { return status; }
        public void setStatus(com.dca.terminal.common.FreshnessStatus status) { this.status = status; }
    }

    @Entity
    @Table(name = "fund_nav_daily")
    public static class FundNavDailyEntity {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "instrument_id", nullable = false)
        private InstrumentEntity instrument;
        @Column(name = "nav_date", nullable = false)
        private LocalDate navDate;
        @Column(nullable = false, precision = 20, scale = 6)
        private BigDecimal nav;
        @Column(nullable = false, length = 32)
        private String source;
        @Column(name = "retrieved_at", nullable = false)
        private Instant retrievedAt;

        public UUID getId() { return id; }
        public InstrumentEntity getInstrument() { return instrument; }
        public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
        public LocalDate getNavDate() { return navDate; }
        public void setNavDate(LocalDate navDate) { this.navDate = navDate; }
        public BigDecimal getNav() { return nav; }
        public void setNav(BigDecimal nav) { this.nav = nav; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public Instant getRetrievedAt() { return retrievedAt; }
        public void setRetrievedAt(Instant retrievedAt) { this.retrievedAt = retrievedAt; }
    }

    @Entity
    @Table(name = "instrument_split")
    public static class SplitEventEntity extends CreatedEntity {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "instrument_id", nullable = false)
        private InstrumentEntity instrument;
        @Column(name = "effective_date", nullable = false)
        private LocalDate effectiveDate;
        @Column(nullable = false, precision = 20, scale = 8)
        private BigDecimal numerator;
        @Column(nullable = false, precision = 20, scale = 8)
        private BigDecimal denominator;
        @Column(nullable = false, length = 32)
        private String source;

        public UUID getId() { return id; }
        public InstrumentEntity getInstrument() { return instrument; }
        public void setInstrument(InstrumentEntity instrument) { this.instrument = instrument; }
        public LocalDate getEffectiveDate() { return effectiveDate; }
        public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
        public BigDecimal getNumerator() { return numerator; }
        public void setNumerator(BigDecimal numerator) { this.numerator = numerator; }
        public BigDecimal getDenominator() { return denominator; }
        public void setDenominator(BigDecimal denominator) { this.denominator = denominator; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }
}
