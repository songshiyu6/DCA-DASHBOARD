package com.dca.terminal.marketdata;

import com.dca.terminal.common.FreshnessStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketMetricsCalculatorTest {

    @Test
    void calculatesAdjustedReturnsAndRaw52WeekExtrema() {
        LocalDate asOf = LocalDate.of(2026, 8, 27);
        List<ProviderModels.PriceBar> bars = List.of(
                bar("2023-08-25", "70", "75", "65", "70"),
                bar("2025-08-27", "110", "110", "90", "100"),
                bar("2025-12-31", "125", "130", "115", "120"),
                bar("2026-01-02", "126", "128", "118", "121"),
                bar("2026-05-26", "135", "140", "125", "135"),
                bar("2026-07-27", "165", "170", "155", "160"),
                bar("2026-08-27", "130", "150", "130", "140"));
        ProviderModels.ProviderQuote quote = new ProviderModels.ProviderQuote(
                bd("141"), bd("140"), null, null,
                Instant.parse("2026-08-27T20:00:00Z"), Instant.parse("2026-08-27T20:00:01Z"));

        MarketMetricsCalculator.Metrics metrics = MarketMetricsCalculator.calculate(bars, quote, asOf);

        assertEquals(FreshnessStatus.FRESH, metrics.status());
        assertDecimal("0.007142857142857142857142857142857143", metrics.oneDay(), "1D");
        assertDecimal("0.1666666666666666666666666666666667", metrics.ytd(), "YTD");
        assertDecimal("170", metrics.fiftyTwoWeekHigh(), "52W high uses raw high");
        assertDecimal("90", metrics.fiftyTwoWeekLow(), "52W low uses raw low");
        assertDecimal("-0.125", metrics.currentDrawdown(), "current drawdown uses adjusted close");
        assertDecimal("-0.125", metrics.maxDrawdown1Y(), "max drawdown uses the one-year adjusted series");
        assertNotNull(metrics.threeYearCagr());
        assertTrue(metrics.threeYearCagr().signum() > 0);

        // The raw close is intentionally different from adjusted close. The return must use adjusted data.
        assertDecimal("0.2", MarketMetricsCalculator.periodReturn(
                List.of(bar("2026-01-02", "110", "111", "109", "100")),
                LocalDate.of(2026, 1, 2), bd("120")), "adjusted period return");
    }

    @Test
    void marksMissing52WeekHighOrLowAsPartial() {
        LocalDate asOf = LocalDate.of(2026, 8, 27);
        List<ProviderModels.PriceBar> bars = List.of(
                bar("2023-08-25", "70", "75", "65", "70"),
                bar("2025-08-27", "110", null, "90", "100"),
                bar("2025-12-31", "125", "130", "115", "120"),
                bar("2026-05-26", "135", "140", "125", "135"),
                bar("2026-07-27", "165", "170", "155", "160"),
                bar("2026-08-27", "130", "150", "130", "140"));

        MarketMetricsCalculator.Metrics metrics = MarketMetricsCalculator.calculate(
                bars, new ProviderModels.ProviderQuote(bd("141"), bd("140"), null, null, null, null), asOf);

        assertEquals(FreshnessStatus.PARTIAL, metrics.status());
        assertNotNull(metrics.fiftyTwoWeekHigh());
        assertNotNull(metrics.fiftyTwoWeekLow());
    }

    @Test
    void doesNotUseAnOlderCalendarYearForYtdBaseline() {
        assertNull(MarketMetricsCalculator.previousYearLastTradingDay(
                List.of(bar("2024-12-31", "100", "101", "99", "100")),
                LocalDate.of(2026, 8, 27)));
    }

    @Test
    void marksEmptyHistoryAsInsufficientRatherThanUnavailable() {
        MarketMetricsCalculator.Metrics metrics = MarketMetricsCalculator.calculate(
                List.of(),
                new ProviderModels.ProviderQuote(bd("100"), bd("99"), null, null, null, null),
                LocalDate.of(2026, 8, 27));

        assertEquals(FreshnessStatus.INSUFFICIENT_HISTORY, metrics.status());
    }

    private static ProviderModels.PriceBar bar(String date, String close, String high, String low, String adjusted) {
        return new ProviderModels.PriceBar(LocalDate.parse(date), bd(close), decimal(high), decimal(low), bd(close),
                decimal(adjusted), 1L);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : bd(value);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertDecimal(String expected, BigDecimal actual, String label) {
        assertNotNull(actual, label + " should be present");
        BigDecimal difference = new BigDecimal(expected).subtract(actual).abs();
        assertTrue(difference.compareTo(new BigDecimal("0.0000000000000000001")) <= 0,
                label + " expected " + expected + " but was " + actual);
    }
}
