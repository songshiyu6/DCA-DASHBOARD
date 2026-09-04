package com.dca.terminal.performance;

import com.dca.terminal.common.FreshnessStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Boundary between the performance engine and the portfolio/cash ledger.
 *
 * <p>All monetary values are portfolio-side amounts. Deposits are positive external flows and withdrawals are
 * negative external flows. BUY/SELL must never appear as external flows. PR A is expected to provide the final
 * cash-inclusive implementation of this contract.</p>
 */
public interface PortfolioPerformanceSource {
    record DailyValuation(LocalDate date, BigDecimal totalValue, BigDecimal cumulativeExternalFlow,
                          FreshnessStatus dataStatus) { }

    record CurrentValuation(LocalDate businessDate, Instant asOf, BigDecimal totalValue,
                            BigDecimal cumulativeExternalFlow, FreshnessStatus dataStatus) { }

    record ExternalCashFlow(LocalDate date, BigDecimal portfolioAmount) { }

    List<DailyValuation> regularCloseHistory();

    CurrentValuation current();

    /**
     * External cash flows only. Positive means capital entered the portfolio; negative means capital left it.
     * The performance engine converts these to investor-perspective XIRR signs and appends the terminal value.
     */
    List<ExternalCashFlow> externalCashFlows();

    /** Human-readable integration marker surfaced by the API for diagnostics. */
    String externalFlowModel();
}
