package com.dca.terminal.plan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionProgressRateTest {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    @Test
    void executionRateUsesFullYearExecutedOverFullYearPlanned() {
        PlanDtos.ContributionProgress progress = new PlanDtos.ContributionProgress(
                2026,
                new BigDecimal("2959.00"),
                new BigDecimal("6000.00"),
                new BigDecimal("3041.00"),
                BigDecimal.ONE,
                List.of());

        BigDecimal expected = new BigDecimal("2959.00").divide(new BigDecimal("6000.00"), MC);
        assertEquals(0, progress.executionRate().compareTo(expected));
    }

    @Test
    void executionRateCanExceedOneWhenAnnualTargetIsExceeded() {
        PlanDtos.ContributionProgress progress = new PlanDtos.ContributionProgress(
                2026,
                new BigDecimal("7200.00"),
                new BigDecimal("6000.00"),
                BigDecimal.ZERO,
                BigDecimal.ONE,
                List.of());

        assertEquals(0, progress.executionRate().compareTo(new BigDecimal("1.2")));
        assertTrue(progress.executionRate().compareTo(BigDecimal.ONE) > 0);
    }

    @Test
    void executionRateIsZeroWhenAnnualPlanIsZero() {
        PlanDtos.ContributionProgress progress = new PlanDtos.ContributionProgress(
                2026,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                List.of());

        assertEquals(0, progress.executionRate().compareTo(BigDecimal.ZERO));
    }
}
