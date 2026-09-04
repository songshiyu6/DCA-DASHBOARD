package com.dca.terminal.performance;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.transaction.XirrCalculator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

import static com.dca.terminal.performance.PerformanceDtos.PerformancePoint;
import static com.dca.terminal.performance.PerformanceDtos.PointType;
import static com.dca.terminal.performance.PerformanceDtos.PortfolioPerformanceResponse;

@Service
public class PerformanceEngine {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    private final PortfolioPerformanceSource source;

    public PerformanceEngine(PortfolioPerformanceSource source) {
        this.source = source;
    }

    public PortfolioPerformanceResponse performance(String rawRange) {
        PerformanceRange range = PerformanceRange.parse(rawRange);
        PortfolioPerformanceSource.CurrentValuation current = source.current();
        List<PortfolioPerformanceSource.DailyValuation> history = source.regularCloseHistory() == null
                ? List.of() : source.regularCloseHistory().stream()
                .filter(item -> item != null && item.date() != null)
                .sorted(Comparator.comparing(PortfolioPerformanceSource.DailyValuation::date))
                .toList();

        List<Valuation> valuations = new ArrayList<>();
        for (PortfolioPerformanceSource.DailyValuation item : history) {
            if (item.dataStatus() != FreshnessStatus.FRESH || !positive(item.totalValue())) continue;
            valuations.add(new Valuation(item.date(), null, item.totalValue(), item.cumulativeExternalFlow(),
                    PointType.REGULAR_CLOSE, item.dataStatus()));
        }
        LocalDate inception = valuations.isEmpty() ? null : valuations.getFirst().date();
        boolean liveIncluded = current != null && current.businessDate() != null && current.asOf() != null
                && positive(current.totalValue()) && current.dataStatus() != FreshnessStatus.UNAVAILABLE;
        if (liveIncluded) {
            valuations.add(new Valuation(current.businessDate(), current.asOf(), current.totalValue(),
                    current.cumulativeExternalFlow(), PointType.LIVE,
                    current.dataStatus() == null ? FreshnessStatus.STALE : current.dataStatus()));
        }
        valuations.sort(Comparator.comparing(Valuation::date)
                .thenComparing(item -> item.pointType() == PointType.REGULAR_CLOSE ? 0 : 1));

        if (valuations.isEmpty()) {
            return new PortfolioPerformanceResponse(range.code(), null, null, null, null,
                    current == null ? null : current.asOf(), null, null, null, null,
                    FreshnessStatus.UNAVAILABLE, false, source.externalFlowModel(), List.of());
        }

        LocalDate endpointDate = current != null && current.businessDate() != null
                ? current.businessDate() : valuations.getLast().date();
        LocalDate requestedStart = range.startDate(endpointDate);
        if (requestedStart == null) requestedStart = inception;
        int baselineIndex = baselineIndex(valuations, requestedStart);
        List<LevelPoint> allLevels = levels(valuations);
        BigDecimal baselineLevel = allLevels.get(baselineIndex).level();

        List<PerformancePoint> points = new ArrayList<>();
        for (int index = baselineIndex; index < allLevels.size(); index++) {
            LevelPoint item = allLevels.get(index);
            if (item.date().isBefore(requestedStart) && index != baselineIndex) continue;
            BigDecimal rebased = divide(item.level(), baselineLevel);
            BigDecimal returnRate = rebased == null ? null : rebased.subtract(BigDecimal.ONE, MC);
            points.add(new PerformancePoint(item.date(), item.asOf(), rebased, returnRate,
                    item.pointType(), item.dataStatus()));
        }

        BigDecimal twr = points.isEmpty() ? null : points.getLast().returnRate();
        BigDecimal cagr = inception == null ? null : annualizedSinceInception(allLevels, inception, endpointDate);
        BigDecimal xirr = xirr(current);
        BigDecimal maximumDrawdown = maximumDrawdown(points);
        FreshnessStatus status = points.size() < 2 ? FreshnessStatus.INSUFFICIENT_HISTORY
                : points.getLast().dataStatus();

        return new PortfolioPerformanceResponse(range.code(), requestedStart,
                valuations.get(baselineIndex).date(), inception, endpointDate,
                current == null ? null : current.asOf(), twr, cagr, xirr, maximumDrawdown,
                status, liveIncluded, source.externalFlowModel(), List.copyOf(points));
    }

    private BigDecimal xirr(PortfolioPerformanceSource.CurrentValuation current) {
        if (current == null || current.businessDate() == null || !positive(current.totalValue())) return null;
        List<PortfolioPerformanceSource.ExternalCashFlow> external = source.externalCashFlows();
        if (external == null || external.isEmpty()) return null;
        List<XirrCalculator.CashFlow> flows = new ArrayList<>();
        for (PortfolioPerformanceSource.ExternalCashFlow flow : external) {
            if (flow == null || flow.date() == null || flow.portfolioAmount() == null
                    || flow.date().isAfter(current.businessDate())) continue;
            flows.add(new XirrCalculator.CashFlow(flow.date(), flow.portfolioAmount().negate()));
        }
        flows.add(new XirrCalculator.CashFlow(current.businessDate(), current.totalValue()));
        return XirrCalculator.solve(flows);
    }

    static List<LevelPoint> levels(List<Valuation> valuations) {
        if (valuations == null || valuations.isEmpty()) return List.of();
        List<LevelPoint> result = new ArrayList<>();
        BigDecimal level = BigDecimal.ONE;
        Valuation previous = valuations.getFirst();
        result.add(new LevelPoint(previous.date(), previous.asOf(), level, previous.pointType(), previous.dataStatus()));
        for (int index = 1; index < valuations.size(); index++) {
            Valuation current = valuations.get(index);
            if (!positive(previous.totalValue()) || !positive(current.totalValue())) continue;
            BigDecimal previousFlow = zero(previous.cumulativeExternalFlow());
            BigDecimal currentFlow = zero(current.cumulativeExternalFlow());
            BigDecimal externalFlow = currentFlow.subtract(previousFlow, MC);
            BigDecimal numerator = current.totalValue().subtract(externalFlow, MC);
            BigDecimal gross = numerator.divide(previous.totalValue(), MC);
            level = level.multiply(gross, MC);
            result.add(new LevelPoint(current.date(), current.asOf(), level, current.pointType(), current.dataStatus()));
            previous = current;
        }
        return List.copyOf(result);
    }

    static BigDecimal maximumDrawdown(List<PerformancePoint> points) {
        if (points == null || points.size() < 2) return null;
        BigDecimal peak = null;
        BigDecimal maximum = BigDecimal.ZERO;
        for (PerformancePoint point : points) {
            if (point == null || !positive(point.level())) continue;
            if (peak == null || point.level().compareTo(peak) > 0) peak = point.level();
            BigDecimal drawdown = point.level().divide(peak, MC).subtract(BigDecimal.ONE, MC);
            if (drawdown.compareTo(maximum) < 0) maximum = drawdown;
        }
        return maximum;
    }

    private static BigDecimal annualizedSinceInception(List<LevelPoint> levels, LocalDate inception, LocalDate endpoint) {
        if (levels == null || levels.size() < 2 || inception == null || endpoint == null) return null;
        long days = ChronoUnit.DAYS.between(inception, endpoint);
        if (days <= 0) return null;
        BigDecimal terminal = levels.getLast().level();
        if (!positive(terminal)) return null;
        double years = days / 365.2425d;
        double result = Math.pow(terminal.doubleValue(), 1d / years) - 1d;
        return Double.isFinite(result) ? BigDecimal.valueOf(result) : null;
    }

    private static int baselineIndex(List<Valuation> valuations, LocalDate requestedStart) {
        int best = 0;
        for (int index = 0; index < valuations.size(); index++) {
            if (valuations.get(index).date().isAfter(requestedStart)) break;
            best = index;
        }
        return best;
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() == 0
                ? null : numerator.divide(denominator, MC);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    record Valuation(LocalDate date, java.time.Instant asOf, BigDecimal totalValue,
                     BigDecimal cumulativeExternalFlow, PointType pointType, FreshnessStatus dataStatus) { }

    record LevelPoint(LocalDate date, java.time.Instant asOf, BigDecimal level,
                      PointType pointType, FreshnessStatus dataStatus) { }
}
