package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    public record IntradaySession(String exchangeTimezoneName,
                                  Instant preStart, Instant preEnd,
                                  Instant regularStart, Instant regularEnd,
                                  Instant postStart, Instant postEnd) { }

    public record IntradayResult(List<IntradayBar> bars,
                                 int rawTimestampCount,
                                 int dateMatchedCount,
                                 int tradingPeriodMatchedCount,
                                 int nonNullCloseCount,
                                 IntradaySession session) {
        public IntradayResult {
            bars = bars == null ? List.of() : List.copyOf(bars);
        }

        public static IntradayResult fromBars(List<IntradayBar> bars) {
            List<IntradayBar> safeBars = bars == null ? List.of() : List.copyOf(bars);
            return new IntradayResult(safeBars, safeBars.size(), safeBars.size(), safeBars.size(), safeBars.size(), null);
        }
    }

    public record EtfProfile(String name, String exchange, String currency, String issuer,
                             BigDecimal expenseRatio, BigDecimal aum, BigDecimal dividendYield) { }

    public record SplitEvent(LocalDate effectiveDate, BigDecimal numerator, BigDecimal denominator) { }
}
