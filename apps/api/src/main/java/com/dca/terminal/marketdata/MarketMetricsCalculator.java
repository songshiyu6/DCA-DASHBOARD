package com.dca.terminal.marketdata;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.common.DecimalMath;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class MarketMetricsCalculator {
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.2425");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    private MarketMetricsCalculator() { }

    public static Metrics calculate(List<ProviderModels.PriceBar> sourceBars,
                                    ProviderModels.ProviderQuote quote, LocalDate asOf) {
        return calculate(sourceBars, quote, asOf,
                quote == null ? FreshnessStatus.UNAVAILABLE : FreshnessStatus.FRESH);
    }

    public static Metrics calculate(List<ProviderModels.PriceBar> sourceBars,
                                    ProviderModels.ProviderQuote quote, LocalDate asOf,
                                    FreshnessStatus quoteStatus) {
        if (sourceBars == null || asOf == null) return Metrics.unavailable();

        List<ProviderModels.PriceBar> bars = sourceBars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> bar.tradeDate() != null && !bar.tradeDate().isAfter(asOf))
                .sorted(Comparator.comparing(ProviderModels.PriceBar::tradeDate))
                .toList();
        if (bars.isEmpty()) return Metrics.insufficientHistory();

        ProviderModels.PriceBar latestBar = bars.get(bars.size() - 1);
        BigDecimal latestAdjusted = adjusted(latestBar);
        BigDecimal latestPrice = quote == null || quote.price() == null ? latestAdjusted : quote.price();
        BigDecimal oneDay = null;
        if (quote != null && quote.previousClose() != null && latestPrice != null) {
            BigDecimal ratio = ratio(latestPrice, quote.previousClose());
            oneDay = ratio == null ? null : ratio.subtract(BigDecimal.ONE, MC);
        }

        BigDecimal oneMonth = periodReturn(bars, asOf.minusMonths(1), latestAdjusted);
        BigDecimal threeMonths = periodReturn(bars, asOf.minusMonths(3), latestAdjusted);
        LocalDate previousYearEnd = previousYearLastTradingDay(bars, asOf);
        BigDecimal ytd = previousYearEnd == null ? null : periodReturn(bars, previousYearEnd, latestAdjusted);
        BigDecimal oneYear = periodReturn(bars, asOf.minusYears(1), latestAdjusted);
        BigDecimal threeYear = cagr(bars, asOf.minusYears(3), latestAdjusted, latestBar.tradeDate());

        LocalDate yearAgo = asOf.minusDays(365);
        List<ProviderModels.PriceBar> yearBars = bars.stream()
                .filter(bar -> !bar.tradeDate().isBefore(yearAgo) && !bar.tradeDate().isAfter(asOf))
                .toList();
        BigDecimal high52 = yearBars.stream().map(ProviderModels.PriceBar::high).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        BigDecimal low52 = yearBars.stream().map(ProviderModels.PriceBar::low).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        boolean incompleteExtrema = !yearBars.isEmpty()
                && yearBars.stream().anyMatch(bar -> bar.high() == null || bar.low() == null);
        BigDecimal currentDrawdown = currentDrawdown(bars, latestAdjusted);
        BigDecimal maxDrawdown = maxDrawdown(yearBars);

        boolean complete = latestAdjusted != null
                && oneMonth != null && threeMonths != null && ytd != null && oneYear != null
                && threeYear != null && !yearBars.isEmpty() && high52 != null && low52 != null
                && currentDrawdown != null && maxDrawdown != null && !incompleteExtrema;
        FreshnessStatus status = complete ? FreshnessStatus.FRESH
                : incompleteExtrema ? FreshnessStatus.PARTIAL : FreshnessStatus.INSUFFICIENT_HISTORY;
        if (complete && quoteStatus != null && quoteStatus != FreshnessStatus.FRESH) status = quoteStatus;
        return new Metrics(oneDay, oneMonth, threeMonths, ytd, oneYear, threeYear, high52, low52,
                currentDrawdown, maxDrawdown, status);
    }

    public static BigDecimal periodReturn(List<ProviderModels.PriceBar> bars, LocalDate targetDate,
                                          BigDecimal end) {
        if (bars == null || targetDate == null || end == null || end.signum() <= 0) return null;
        ProviderModels.PriceBar start = bars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> bar.tradeDate() != null && !bar.tradeDate().isAfter(targetDate))
                .filter(bar -> adjusted(bar) != null && adjusted(bar).signum() > 0)
                .max(Comparator.comparing(ProviderModels.PriceBar::tradeDate)).orElse(null);
        if (start == null) return null;
        BigDecimal ratio = ratio(end, adjusted(start));
        return ratio == null ? null : ratio.subtract(BigDecimal.ONE, MC);
    }

    public static BigDecimal cagr(List<ProviderModels.PriceBar> bars, LocalDate targetDate,
                                  BigDecimal end, LocalDate endDate) {
        if (bars == null || targetDate == null || end == null || endDate == null || end.signum() <= 0) return null;
        ProviderModels.PriceBar start = bars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> bar.tradeDate() != null && !bar.tradeDate().isAfter(targetDate))
                .filter(bar -> adjusted(bar) != null && adjusted(bar).signum() > 0)
                .max(Comparator.comparing(ProviderModels.PriceBar::tradeDate)).orElse(null);
        if (start == null) return null;
        long days = ChronoUnit.DAYS.between(start.tradeDate(), endDate);
        if (days <= 0) return null;
        BigDecimal priceRatio = ratio(end, adjusted(start));
        if (priceRatio == null || priceRatio.signum() <= 0) return null;
        BigDecimal exponent = DAYS_PER_YEAR.divide(BigDecimal.valueOf(days), MC);
        BigDecimal result = DecimalMath.pow(priceRatio, exponent);
        return result == null ? null : result.subtract(BigDecimal.ONE, MC);
    }

    public static BigDecimal currentDrawdown(List<ProviderModels.PriceBar> bars, BigDecimal latest) {
        if (bars == null || latest == null || latest.signum() <= 0) return null;
        BigDecimal peak = bars.stream().map(MarketMetricsCalculator::adjusted).filter(Objects::nonNull)
                .filter(value -> value.signum() > 0).max(Comparator.naturalOrder()).orElse(null);
        BigDecimal ratio = ratio(latest, peak);
        return ratio == null ? null : ratio.subtract(BigDecimal.ONE, MC);
    }

    public static BigDecimal maxDrawdown(List<ProviderModels.PriceBar> bars) {
        if (bars == null || bars.isEmpty()) return null;
        BigDecimal peak = null;
        BigDecimal minimum = BigDecimal.ZERO;
        boolean found = false;
        for (ProviderModels.PriceBar bar : bars) {
            if (bar == null) continue;
            BigDecimal price = adjusted(bar);
            if (price == null || price.signum() <= 0) continue;
            found = true;
            peak = peak == null || price.compareTo(peak) > 0 ? price : peak;
            BigDecimal ratio = ratio(price, peak);
            if (ratio != null) {
                BigDecimal drawdown = ratio.subtract(BigDecimal.ONE, MC);
                if (drawdown.compareTo(minimum) < 0) minimum = drawdown;
            }
        }
        return found ? minimum : null;
    }

    public static LocalDate previousYearLastTradingDay(List<ProviderModels.PriceBar> bars, LocalDate asOf) {
        if (bars == null || asOf == null) return null;
        return bars.stream().filter(Objects::nonNull).map(ProviderModels.PriceBar::tradeDate)
                .filter(Objects::nonNull)
                .filter(date -> date.getYear() == asOf.getYear() - 1)
                .max(LocalDate::compareTo).orElse(null);
    }

    public static BigDecimal adjusted(ProviderModels.PriceBar bar) {
        return bar == null ? null : bar.adjustedClose() == null ? bar.close() : bar.adjustedClose();
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) return null;
        return numerator.divide(denominator, MC);
    }

    public record Metrics(BigDecimal oneDay, BigDecimal oneMonth, BigDecimal threeMonths, BigDecimal ytd,
                          BigDecimal oneYear, BigDecimal threeYearCagr, BigDecimal fiftyTwoWeekHigh,
                          BigDecimal fiftyTwoWeekLow, BigDecimal currentDrawdown, BigDecimal maxDrawdown1Y,
                          FreshnessStatus status) {
        public static Metrics unavailable() {
            return new Metrics(null, null, null, null, null, null, null, null, null, null,
                    FreshnessStatus.UNAVAILABLE);
        }

        public static Metrics insufficientHistory() {
            return new Metrics(null, null, null, null, null, null, null, null, null, null,
                    FreshnessStatus.INSUFFICIENT_HISTORY);
        }
    }
}
