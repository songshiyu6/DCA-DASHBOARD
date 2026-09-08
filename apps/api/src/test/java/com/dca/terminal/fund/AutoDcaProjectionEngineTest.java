package com.dca.terminal.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoDcaProjectionEngineTest {

    @Test
    void derivesDailyPurchasesFromObservedNavDatesAndTracksConfirmationLag() {
        AutoDcaProjectionEngine.RuleSpec rule = new AutoDcaProjectionEngine.RuleSpec(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 6),
                new BigDecimal("100"), new BigDecimal("0.0015"), 1);

        AutoDcaProjectionEngine.Projection projection = AutoDcaProjectionEngine.project(rule, List.of(
                nav("2026-07-01", "1.0000"),
                nav("2026-07-02", "1.1000"),
                nav("2026-07-03", "1.2000"),
                nav("2026-07-06", "1.3000")
        ), AutoDcaProjectionEngine.GroupBy.MONTH);

        assertEquals(4, projection.daily().size());
        assertEquals(LocalDate.of(2026, 7, 2), projection.daily().getFirst().confirmationDate());
        assertEquals(AutoDcaProjectionEngine.ConfirmationStatus.CONFIRMED,
                projection.daily().getFirst().status());
        assertNull(projection.daily().getLast().confirmationDate());
        assertEquals(AutoDcaProjectionEngine.ConfirmationStatus.PENDING_CONFIRMATION,
                projection.daily().getLast().status());
        assertTrue(projection.daily().getFirst().purchaseFee().signum() > 0);
        assertTrue(projection.daily().getFirst().netSubscribedAmount()
                .compareTo(projection.daily().getFirst().grossAmount()) < 0);

        AutoDcaProjectionEngine.Summary july = projection.summaries().getFirst();
        assertEquals("2026-07", july.period());
        assertEquals(4, july.executionCount());
        assertEquals(3, july.confirmedCount());
        assertMoney("400.000000", july.grossAmount());
        assertTrue(july.shares().signum() > 0);
        assertTrue(july.averageCostPerShare().compareTo(july.averageNav()) > 0);
    }

    @Test
    void aggregatesDailyDcaIntoMonthlyAndYearlyViews() {
        AutoDcaProjectionEngine.RuleSpec rule = new AutoDcaProjectionEngine.RuleSpec(
                LocalDate.of(2026, 7, 1), LocalDate.of(2027, 1, 31),
                new BigDecimal("10"), BigDecimal.ZERO, 0);
        List<AutoDcaProjectionEngine.NavPoint> nav = List.of(
                nav("2026-07-01", "1"),
                nav("2026-07-02", "1"),
                nav("2026-08-03", "1"),
                nav("2027-01-04", "1")
        );

        AutoDcaProjectionEngine.Projection monthly = AutoDcaProjectionEngine.project(
                rule, nav, AutoDcaProjectionEngine.GroupBy.MONTH);
        AutoDcaProjectionEngine.Projection yearly = AutoDcaProjectionEngine.project(
                rule, nav, AutoDcaProjectionEngine.GroupBy.YEAR);

        assertEquals(3, monthly.summaries().size());
        assertEquals("2026-07", monthly.summaries().getFirst().period());
        assertEquals(2, monthly.summaries().getFirst().executionCount());
        assertMoney("20.000000", monthly.summaries().getFirst().grossAmount());

        assertEquals(2, yearly.summaries().size());
        assertEquals("2026", yearly.summaries().getFirst().period());
        assertEquals(3, yearly.summaries().getFirst().executionCount());
        assertMoney("30.000000", yearly.summaries().getFirst().grossAmount());
        assertEquals("2027", yearly.summaries().getLast().period());
        assertMoney("10.000000", yearly.summaries().getLast().grossAmount());
    }

    @Test
    void rewritingStartDateOrAmountRecomputesHistoryWithoutPersistedDailyRows() {
        List<AutoDcaProjectionEngine.NavPoint> nav = List.of(
                nav("2026-07-01", "1"), nav("2026-07-02", "1"),
                nav("2026-07-03", "1"), nav("2026-07-06", "1"));

        AutoDcaProjectionEngine.Projection original = AutoDcaProjectionEngine.project(
                new AutoDcaProjectionEngine.RuleSpec(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 6),
                        new BigDecimal("100"), BigDecimal.ZERO, 0),
                nav, AutoDcaProjectionEngine.GroupBy.MONTH);
        AutoDcaProjectionEngine.Projection rewritten = AutoDcaProjectionEngine.project(
                new AutoDcaProjectionEngine.RuleSpec(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 6),
                        new BigDecimal("200"), BigDecimal.ZERO, 0),
                nav, AutoDcaProjectionEngine.GroupBy.MONTH);

        assertEquals(4, original.daily().size());
        assertEquals(2, rewritten.daily().size());
        assertMoney("400.000000", original.summaries().getFirst().grossAmount());
        assertMoney("400.000000", rewritten.summaries().getFirst().grossAmount());
        assertEquals(LocalDate.of(2026, 7, 3), rewritten.daily().getFirst().navDate());
    }

    private static AutoDcaProjectionEngine.NavPoint nav(String date, String nav) {
        return new AutoDcaProjectionEngine.NavPoint(LocalDate.parse(date), new BigDecimal(nav));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
