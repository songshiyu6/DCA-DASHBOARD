package com.dca.terminal.fund;

import com.dca.terminal.common.DecimalMath;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rebuildable automatic-DCA projection for China mutual funds.
 *
 * A rule is the source fact. A NAV date is treated as an observed fund valuation/open date for the
 * historical projection. No investment_transaction row is created here.
 */
public final class AutoDcaProjectionEngine {
    private static final MathContext MC = DecimalMath.MC;

    private AutoDcaProjectionEngine() { }

    public enum GroupBy { MONTH, YEAR }
    public enum ConfirmationStatus { CONFIRMED, PENDING_CONFIRMATION }

    public record RuleSpec(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal amount,
            BigDecimal purchaseFeeRate,
            int confirmationTradingDays) { }

    public record NavPoint(LocalDate date, BigDecimal nav) { }

    public record Execution(
            LocalDate orderDate,
            LocalDate navDate,
            LocalDate confirmationDate,
            BigDecimal nav,
            BigDecimal grossAmount,
            BigDecimal purchaseFee,
            BigDecimal netSubscribedAmount,
            BigDecimal shares,
            ConfirmationStatus status) { }

    public record Summary(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            int executionCount,
            int confirmedCount,
            BigDecimal grossAmount,
            BigDecimal purchaseFees,
            BigDecimal netSubscribedAmount,
            BigDecimal shares,
            BigDecimal averageNav,
            BigDecimal averageCostPerShare,
            BigDecimal latestNav,
            BigDecimal currentValue,
            BigDecimal currentPnl,
            BigDecimal returnRate) { }

    public record Projection(
            List<Execution> daily,
            List<Summary> summaries,
            BigDecimal latestNav,
            LocalDate latestNavDate) { }

    public static Projection project(RuleSpec rule, List<NavPoint> navPoints, GroupBy groupBy) {
        validateRule(rule);
        TreeMap<LocalDate, BigDecimal> navByDate = normalizeNav(navPoints);
        if (navByDate.isEmpty()) return new Projection(List.of(), List.of(), null, null);

        List<LocalDate> observedDates = new ArrayList<>(navByDate.keySet());
        LocalDate latestNavDate = observedDates.getLast();
        BigDecimal latestNav = navByDate.get(latestNavDate);
        LocalDate effectiveEnd = rule.endDate() == null || rule.endDate().isAfter(latestNavDate)
                ? latestNavDate : rule.endDate();

        List<Execution> daily = new ArrayList<>();
        for (int index = 0; index < observedDates.size(); index++) {
            LocalDate navDate = observedDates.get(index);
            if (navDate.isBefore(rule.startDate()) || navDate.isAfter(effectiveEnd)) continue;
            BigDecimal nav = navByDate.get(navDate);
            BigDecimal net = rule.amount().divide(BigDecimal.ONE.add(rule.purchaseFeeRate(), MC), MC);
            BigDecimal fee = rule.amount().subtract(net, MC);
            BigDecimal shares = net.divide(nav, MC).setScale(DecimalMath.QUANTITY_SCALE, RoundingMode.HALF_UP);
            int confirmationIndex = index + rule.confirmationTradingDays();
            LocalDate confirmationDate = confirmationIndex < observedDates.size()
                    ? observedDates.get(confirmationIndex) : null;
            ConfirmationStatus status = confirmationDate == null
                    ? ConfirmationStatus.PENDING_CONFIRMATION : ConfirmationStatus.CONFIRMED;
            daily.add(new Execution(navDate, navDate, confirmationDate, nav,
                    DecimalMath.money(rule.amount()), DecimalMath.money(fee), DecimalMath.money(net), shares, status));
        }

        return new Projection(List.copyOf(daily), summarize(daily, groupBy, latestNav), latestNav, latestNavDate);
    }

    private static List<Summary> summarize(List<Execution> daily, GroupBy groupBy, BigDecimal latestNav) {
        Map<String, List<Execution>> groups = new LinkedHashMap<>();
        for (Execution execution : daily) {
            String key = groupBy == GroupBy.YEAR
                    ? Integer.toString(execution.navDate().getYear())
                    : YearMonth.from(execution.navDate()).toString();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(execution);
        }
        List<Summary> result = new ArrayList<>();
        groups.forEach((period, executions) -> result.add(summary(period, executions, latestNav)));
        return List.copyOf(result);
    }

    private static Summary summary(String period, List<Execution> executions, BigDecimal latestNav) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal shares = BigDecimal.ZERO;
        int confirmed = 0;
        for (Execution execution : executions) {
            gross = gross.add(execution.grossAmount(), MC);
            fees = fees.add(execution.purchaseFee(), MC);
            net = net.add(execution.netSubscribedAmount(), MC);
            shares = shares.add(execution.shares(), MC);
            if (execution.status() == ConfirmationStatus.CONFIRMED) confirmed++;
        }
        BigDecimal averageNav = shares.signum() == 0 ? null : net.divide(shares, MC);
        BigDecimal averageCost = shares.signum() == 0 ? null : gross.divide(shares, MC);
        BigDecimal currentValue = latestNav == null ? null : shares.multiply(latestNav, MC);
        BigDecimal pnl = currentValue == null ? null : currentValue.subtract(gross, MC);
        BigDecimal returnRate = pnl == null || gross.signum() == 0 ? null : pnl.divide(gross, MC);
        return new Summary(period, executions.getFirst().navDate(), executions.getLast().navDate(),
                executions.size(), confirmed, DecimalMath.money(gross), DecimalMath.money(fees),
                DecimalMath.money(net), shares.setScale(DecimalMath.QUANTITY_SCALE, RoundingMode.HALF_UP),
                averageNav, averageCost, latestNav, DecimalMath.money(currentValue), DecimalMath.money(pnl), returnRate);
    }

    private static TreeMap<LocalDate, BigDecimal> normalizeNav(List<NavPoint> navPoints) {
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        if (navPoints == null) return result;
        for (NavPoint point : navPoints) {
            if (point == null || point.date() == null || point.nav() == null || point.nav().signum() <= 0) continue;
            result.put(point.date(), point.nav());
        }
        return result;
    }

    private static void validateRule(RuleSpec rule) {
        if (rule == null || rule.startDate() == null || rule.amount() == null || rule.amount().signum() <= 0) {
            throw new IllegalArgumentException("Auto DCA rule requires start date and positive amount");
        }
        if (rule.endDate() != null && rule.endDate().isBefore(rule.startDate())) {
            throw new IllegalArgumentException("Auto DCA end date cannot be before start date");
        }
        if (rule.purchaseFeeRate() == null || rule.purchaseFeeRate().signum() < 0
                || rule.purchaseFeeRate().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Purchase fee rate must be between 0 and 1");
        }
        if (rule.confirmationTradingDays() < 0 || rule.confirmationTradingDays() > 10) {
            throw new IllegalArgumentException("Confirmation trading days must be between 0 and 10");
        }
    }
}
