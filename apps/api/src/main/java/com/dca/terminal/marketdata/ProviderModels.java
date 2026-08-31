package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class ProviderModels {
    private ProviderModels() { }

    public record ProviderSearchResult(String symbol, String name, String exchange, String currency,
                                       InstrumentType type) { }

    public record ProviderQuote(BigDecimal price, BigDecimal previousClose, BigDecimal bid, BigDecimal ask,
                                Instant marketTimestamp, Instant retrievedAt, QuoteSession session) {
        public ProviderQuote(BigDecimal price, BigDecimal previousClose, BigDecimal bid, BigDecimal ask,
                             Instant marketTimestamp, Instant retrievedAt) {
            this(price, previousClose, bid, ask, marketTimestamp, retrievedAt, QuoteSession.UNKNOWN);
        }
    }

    public record PriceBar(LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low,
                           BigDecimal close, BigDecimal adjustedClose, Long volume) { }

    public record IntradayBar(Instant timestamp, BigDecimal open, BigDecimal high, BigDecimal low,
                              BigDecimal close, Long volume) { }

    public record EtfProfile(String name, String exchange, String currency, String issuer,
                             BigDecimal expenseRatio, BigDecimal aum, BigDecimal dividendYield) { }

    public record SplitEvent(LocalDate effectiveDate, BigDecimal numerator, BigDecimal denominator) { }
}
