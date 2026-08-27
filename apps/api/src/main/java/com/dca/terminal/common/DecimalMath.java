package com.dca.terminal.common;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class DecimalMath {
    public static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    public static final int MONEY_SCALE = 6;
    public static final int DISPLAY_MONEY_SCALE = 2;
    public static final int QUANTITY_SCALE = 8;

    private DecimalMath() {
    }

    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal percent(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(100), MC);
    }

    /**
     * BigDecimal has no fractional-power primitive. Integer exponents stay
     * entirely decimal; fractional exponents use the JDK's correctly rounded
     * double power as the isolated transcendental boundary and are converted
     * back to the application MathContext immediately.
     */
    public static BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        if (base == null || exponent == null || base.signum() <= 0) return null;
        BigDecimal normalizedExponent = exponent.stripTrailingZeros();
        if (normalizedExponent.scale() <= 0 && normalizedExponent.precision() < 10) {
            try {
                return base.pow(normalizedExponent.intValueExact(), MC);
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        double baseDouble = base.doubleValue();
        double exponentDouble = exponent.doubleValue();
        if (!Double.isFinite(baseDouble) || !Double.isFinite(exponentDouble)) return null;
        double result = Math.pow(baseDouble, exponentDouble);
        return Double.isFinite(result) ? BigDecimal.valueOf(result).round(MC) : null;
    }
}
