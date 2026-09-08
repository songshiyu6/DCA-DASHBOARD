package com.dca.terminal.reporting;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.performance.PerformanceDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MultiCurrencyDtos {
    private MultiCurrencyDtos() { }

    public record FundPosition(
            String fundCode,
            String fundName,
            BigDecimal shares,
            BigDecimal nav,
            LocalDate navDate,
            BigDecimal marketValueCny,
            BigDecimal marketValueUsd) { }

    public record Summary(
            String reportingCurrency,
            BigDecimal usdAccountValue,
            BigDecimal cnyFundValue,
            BigDecimal cnyFundValueUsd,
            BigDecimal combinedValueUsd,
            BigDecimal usdExternalFlow,
            BigDecimal cnyAutoDcaExternalFlowUsd,
            BigDecimal combinedExternalFlowUsd,
            BigDecimal combinedPnlUsd,
            BigDecimal usdCnyRate,
            LocalDate usdCnyRateDate,
            FreshnessStatus status,
            Instant asOf,
            List<FundPosition> funds) { }

    public record Response(Summary summary, PerformanceDtos.PortfolioPerformanceResponse performance) { }
}
