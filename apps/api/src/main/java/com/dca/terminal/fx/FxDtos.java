package com.dca.terminal.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class FxDtos {
    private FxDtos() { }

    public record FxRateResponse(
            String baseCurrency,
            String quoteCurrency,
            LocalDate rateDate,
            BigDecimal rate,
            String source,
            Instant retrievedAt) { }

    public record FxSyncResponse(
            String baseCurrency,
            String quoteCurrency,
            LocalDate startDate,
            LocalDate endDate,
            int persistedRows,
            LocalDate latestRateDate,
            BigDecimal latestRate,
            String source) { }

    public record FxSeriesResponse(
            String baseCurrency,
            String quoteCurrency,
            String semantics,
            List<FxRateResponse> rates) { }
}
