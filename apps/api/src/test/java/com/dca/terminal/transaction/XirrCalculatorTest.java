package com.dca.terminal.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XirrCalculatorTest {

    @Test
    void sameDayFundingAndLiquidationDoNotHaveAnAnnualizedReturn() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        assertNull(XirrCalculator.solve(List.of(
                new XirrCalculator.CashFlow(date, new BigDecimal("-1000")),
                new XirrCalculator.CashFlow(date, new BigDecimal("1000")))));
    }

    @Test
    void cancellingFlowsOnDifferentDatesDoNotInventARoot() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        assertNull(XirrCalculator.solve(List.of(
                new XirrCalculator.CashFlow(date, new BigDecimal("-1000")),
                new XirrCalculator.CashFlow(date, new BigDecimal("1000")),
                new XirrCalculator.CashFlow(date.plusDays(1), new BigDecimal("-200")),
                new XirrCalculator.CashFlow(date.plusDays(1), new BigDecimal("200")))));
    }

    @Test
    void longHoldingPeriodsSurviveOverflowAtExtremeProbeRates() {
        LocalDate date = LocalDate.of(1980, 1, 1);
        BigDecimal result = XirrCalculator.solve(List.of(
                new XirrCalculator.CashFlow(date, new BigDecimal("-1000")),
                new XirrCalculator.CashFlow(date.plusDays(14610), new BigDecimal("2000"))));
        assertNotNull(result);
        assertTrue(Math.abs(result.doubleValue() - (Math.pow(2, 365d / 14610) - 1)) < 1e-9);
    }

    @Test
    void solvesAOneYearTenPercentReturn() {
        BigDecimal result = XirrCalculator.solve(List.of(
                new XirrCalculator.CashFlow(LocalDate.of(2023, 1, 1), new BigDecimal("-1000")),
                new XirrCalculator.CashFlow(LocalDate.of(2024, 1, 1), new BigDecimal("1100"))));

        assertNotNull(result);
        assertTrue(result.subtract(new BigDecimal("0.10")).abs().compareTo(new BigDecimal("0.000000001")) < 0,
                "XIRR was " + result);
    }

    @Test
    void selectsTheRootClosestToZeroWhenCashFlowsHaveMultipleRoots() {
        LocalDate origin = LocalDate.of(2023, 1, 1);
        BigDecimal result = XirrCalculator.solve(List.of(
                new XirrCalculator.CashFlow(origin, new BigDecimal("-100")),
                new XirrCalculator.CashFlow(origin.plusDays(365), new BigDecimal("230")),
                new XirrCalculator.CashFlow(origin.plusDays(730), new BigDecimal("-132"))));

        assertNotNull(result);
        assertTrue(result.subtract(new BigDecimal("0.10")).abs().compareTo(new BigDecimal("0.000000001")) < 0,
                "XIRR was " + result);
        assertTrue(XirrCalculator.npv(List.of(
                new XirrCalculator.CashFlow(origin, new BigDecimal("-100")),
                new XirrCalculator.CashFlow(origin.plusDays(365), new BigDecimal("230")),
                new XirrCalculator.CashFlow(origin.plusDays(730), new BigDecimal("-132"))),
                origin, new BigDecimal("0.20")).abs().compareTo(new BigDecimal("0.000000001")) < 0);
    }

    @Test
    void returnsNullWhenCashFlowsDoNotContainBothSigns() {
        assertNull(XirrCalculator.solve(List.of(
                new XirrCalculator.CashFlow(LocalDate.of(2023, 1, 1), new BigDecimal("100")))));
    }

    @Test
    void rejectsRatesAtOrBelowMinusOne() {
        assertNull(XirrCalculator.npv(
                List.of(new XirrCalculator.CashFlow(LocalDate.of(2023, 1, 1), new BigDecimal("100"))),
                LocalDate.of(2023, 1, 1), new BigDecimal("-1")));
    }
}
