package com.dca.terminal.performance;

import com.dca.terminal.common.FreshnessStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceEngineTest {

    @Test
    void depositChangesCapitalButNotPerformance() {
        StubSource source = new StubSource(
                List.of(
                        day("2026-09-01", "1000", "1000"),
                        day("2026-09-02", "1100", "1000")
                ),
                current("2026-09-03", "1700", "1500", FreshnessStatus.FRESH),
                List.of(flow("2026-09-01", "1000"), flow("2026-09-03", "500"))
        );

        var result = new PerformanceEngine(source).performance("ALL");

        assertThat(result.twr()).isEqualByComparingTo("0.2");
        assertThat(result.liveEndpointIncluded()).isTrue();
        assertThat(result.externalFlowModel()).isEqualTo(CashLedgerPortfolioPerformanceSource.EXTERNAL_FLOW_MODEL);
        assertThat(result.points()).hasSize(3);
    }

    @Test
    void withdrawalDoesNotCreateAnInvestmentLoss() {
        StubSource source = new StubSource(
                List.of(
                        day("2026-09-01", "1000", "1000"),
                        day("2026-09-02", "1100", "1000")
                ),
                current("2026-09-03", "600", "500", FreshnessStatus.FRESH),
                List.of(flow("2026-09-01", "1000"), flow("2026-09-03", "-500"))
        );

        var result = new PerformanceEngine(source).performance("ALL");

        assertThat(result.twr()).isEqualByComparingTo("0.1");
    }

    @Test
    void partialLiveValuationIsNeverUsedAsPerformanceEndpoint() {
        StubSource source = new StubSource(
                List.of(
                        day("2026-09-01", "1000", "1000"),
                        day("2026-09-02", "1100", "1000")
                ),
                current("2026-09-03", "500", "1000", FreshnessStatus.PARTIAL),
                List.of(flow("2026-09-01", "1000"))
        );

        var result = new PerformanceEngine(source).performance("ALL");

        assertThat(result.liveEndpointIncluded()).isFalse();
        assertThat(result.endpointDate()).isEqualTo(LocalDate.parse("2026-09-02"));
        assertThat(result.twr()).isEqualByComparingTo("0.1");
        assertThat(result.xirr()).isNull();
        assertThat(result.points()).hasSize(2);
    }

    @Test
    void maximumDrawdownUsesSelectedPerformanceLevels() {
        StubSource source = new StubSource(
                List.of(
                        day("2026-09-01", "1000", "1000"),
                        day("2026-09-02", "1200", "1000"),
                        day("2026-09-03", "900", "1000")
                ),
                null,
                List.of(flow("2026-09-01", "1000"))
        );

        var result = new PerformanceEngine(source).performance("ALL");

        assertThat(result.maximumDrawdown()).isEqualByComparingTo("-0.25");
    }

    @Test
    void xirrUsesExternalCashFlowsAndLiveTerminalValue() {
        StubSource source = new StubSource(
                List.of(day("2025-09-04", "1000", "1000")),
                current("2026-09-04", "1100", "1000", FreshnessStatus.FRESH),
                List.of(flow("2025-09-04", "1000"))
        );

        var result = new PerformanceEngine(source).performance("ALL");

        assertThat(result.xirr()).isNotNull();
        assertThat(result.xirr().doubleValue()).isBetween(0.09, 0.11);
    }

    private static PortfolioPerformanceSource.DailyValuation day(String date, String value, String flow) {
        return new PortfolioPerformanceSource.DailyValuation(LocalDate.parse(date), new BigDecimal(value),
                new BigDecimal(flow), FreshnessStatus.FRESH);
    }

    private static PortfolioPerformanceSource.CurrentValuation current(String date, String value, String flow,
                                                                        FreshnessStatus status) {
        return new PortfolioPerformanceSource.CurrentValuation(LocalDate.parse(date),
                Instant.parse(date + "T16:30:00Z"), new BigDecimal(value), new BigDecimal(flow), status);
    }

    private static PortfolioPerformanceSource.ExternalCashFlow flow(String date, String amount) {
        return new PortfolioPerformanceSource.ExternalCashFlow(LocalDate.parse(date), new BigDecimal(amount));
    }

    private static final class StubSource implements PortfolioPerformanceSource {
        private final List<DailyValuation> history;
        private final CurrentValuation current;
        private final List<ExternalCashFlow> flows;

        private StubSource(List<DailyValuation> history, CurrentValuation current, List<ExternalCashFlow> flows) {
            this.history = history;
            this.current = current;
            this.flows = flows;
        }

        @Override
        public List<DailyValuation> regularCloseHistory() {
            return history;
        }

        @Override
        public CurrentValuation current() {
            return current;
        }

        @Override
        public List<ExternalCashFlow> externalCashFlows() {
            return flows;
        }

        @Override
        public String externalFlowModel() {
            return CashLedgerPortfolioPerformanceSource.EXTERNAL_FLOW_MODEL;
        }
    }
}
