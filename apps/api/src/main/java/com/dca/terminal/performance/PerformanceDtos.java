package com.dca.terminal.performance;

import com.dca.terminal.common.FreshnessStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PerformanceDtos {
    private PerformanceDtos() { }

    public enum PointType {
        REGULAR_CLOSE,
        LIVE
    }

    public record PerformancePoint(LocalDate date, Instant asOf, BigDecimal level, BigDecimal returnRate,
                                   PointType pointType, FreshnessStatus dataStatus) { }

    public record PortfolioPerformanceResponse(String range, LocalDate requestedStartDate,
                                               LocalDate baselineDate, LocalDate inceptionDate,
                                               LocalDate endpointDate, Instant asOf,
                                               BigDecimal twr, BigDecimal cagr, BigDecimal xirr,
                                               BigDecimal maximumDrawdown,
                                               FreshnessStatus dataStatus,
                                               boolean liveEndpointIncluded,
                                               String externalFlowModel,
                                               List<PerformancePoint> points) { }
}
