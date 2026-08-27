package com.dca.terminal.transaction;

import com.dca.terminal.common.DecimalMath;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class XirrCalculator {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final BigDecimal MIN_RATE = new BigDecimal("-0.999999999");
    private static final BigDecimal MAX_RATE = new BigDecimal("1000000000000");
    private static final BigDecimal EPSILON = new BigDecimal("0.000000000001");

    private XirrCalculator() { }

    public static BigDecimal solve(List<CashFlow> flows) {
        if (flows == null || flows.isEmpty()
                || flows.stream().anyMatch(flow -> flow == null || flow.date() == null || flow.amount() == null)
                || flows.stream().noneMatch(flow -> flow.amount().signum() < 0)
                || flows.stream().noneMatch(flow -> flow.amount().signum() > 0)) return null;

        List<CashFlow> ordered = flows.stream().sorted(Comparator.comparing(CashFlow::date)).toList();
        LocalDate origin = ordered.get(0).date();
        Bracket bracket = findBracket(ordered, origin);
        if (bracket == null) return null;
        BigDecimal left = bracket.left();
        BigDecimal right = bracket.right();
        BigDecimal leftValue = bracket.leftValue();
        BigDecimal rightValue = bracket.rightValue();

        for (int i = 0; i < 200; i++) {
            BigDecimal middle = left.add(right, MC).divide(BigDecimal.valueOf(2), MC);
            BigDecimal middleValue = npv(ordered, origin, middle);
            if (middleValue == null) return null;
            if (middleValue.abs().compareTo(EPSILON) < 0
                    || right.subtract(left, MC).abs().compareTo(EPSILON) < 0) return middle;
            if (middleValue.signum() == 0) return middle;
            if (sameSign(middleValue, leftValue)) {
                left = middle;
                leftValue = middleValue;
            } else {
                right = middle;
                rightValue = middleValue;
            }
        }
        return left.add(right, MC).divide(BigDecimal.valueOf(2), MC);
    }

    public static BigDecimal npv(List<CashFlow> flows, LocalDate origin, BigDecimal rate) {
        if (flows == null || origin == null || rate == null || rate.compareTo(MIN_RATE) < 0) return null;
        BigDecimal base = BigDecimal.ONE.add(rate, MC);
        if (base.signum() <= 0) return null;
        BigDecimal value = BigDecimal.ZERO;
        for (CashFlow flow : flows) {
            if (flow == null || flow.date() == null || flow.amount() == null) return null;
            long days = ChronoUnit.DAYS.between(origin, flow.date());
            BigDecimal exponent = BigDecimal.valueOf(days).divide(BigDecimal.valueOf(365), MC);
            BigDecimal discount = DecimalMath.pow(base, exponent);
            if (discount == null || discount.signum() == 0) return null;
            value = value.add(flow.amount().divide(discount, MC), MC);
        }
        return value.round(MC);
    }

    private static Bracket findBracket(List<CashFlow> flows, LocalDate origin) {
        List<BigDecimal> probes = new ArrayList<>(List.of(
                MIN_RATE, new BigDecimal("-0.99"), new BigDecimal("-0.9"), new BigDecimal("-0.75"),
                new BigDecimal("-0.5"), new BigDecimal("-0.25"), BigDecimal.ZERO, new BigDecimal("0.1"),
                new BigDecimal("0.25"), BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("5"),
                new BigDecimal("10")));
        BigDecimal rate = new BigDecimal("10");
        while (rate.compareTo(MAX_RATE) < 0) {
            rate = rate.multiply(BigDecimal.valueOf(2), MC).add(BigDecimal.ONE, MC).min(MAX_RATE);
            probes.add(rate);
        }
        List<Bracket> brackets = new ArrayList<>();
        BigDecimal previousRate = probes.get(0);
        BigDecimal previousValue = npv(flows, origin, previousRate);
        if (previousValue == null) return null;
        if (previousValue.signum() == 0) return new Bracket(previousRate, previousRate, previousValue, previousValue);
        for (int index = 1; index < probes.size(); index++) {
            BigDecimal currentRate = probes.get(index);
            BigDecimal currentValue = npv(flows, origin, currentRate);
            if (currentValue == null) return null;
            if (currentValue.signum() == 0) return new Bracket(currentRate, currentRate, currentValue, currentValue);
            if (previousValue.signum() != currentValue.signum()) {
                brackets.add(new Bracket(previousRate, currentRate, previousValue, currentValue));
            }
            previousRate = currentRate;
            previousValue = currentValue;
        }
        return brackets.stream().min(Comparator.comparing(Bracket::distanceFromZero)
                .thenComparing(Bracket::left)).orElse(null);
    }

    private static boolean sameSign(BigDecimal first, BigDecimal second) {
        return first.signum() == second.signum();
    }

    private record Bracket(BigDecimal left, BigDecimal right, BigDecimal leftValue, BigDecimal rightValue) {
        BigDecimal distanceFromZero() { return left.add(right, MC).divide(BigDecimal.valueOf(2), MC).abs(); }
    }

    public record CashFlow(LocalDate date, BigDecimal amount) { }
}
