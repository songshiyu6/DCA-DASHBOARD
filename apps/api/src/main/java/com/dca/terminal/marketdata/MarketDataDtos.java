package com.dca.terminal.marketdata;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.dca.terminal.common.FreshnessStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MarketDataDtos {
    private MarketDataDtos() { }

    public record MetricsResponse(BigDecimal oneDay, BigDecimal oneMonth, BigDecimal threeMonths,
                                  BigDecimal ytd, BigDecimal oneYear, BigDecimal threeYearCagr,
                                  BigDecimal fiftyTwoWeekHigh, BigDecimal fiftyTwoWeekLow,
                                  BigDecimal currentDrawdown, BigDecimal maxDrawdown1Y,
                                  @JsonProperty("dataStatus") FreshnessStatus status, LocalDate asOf) { }

    public record PricePoint(String date, BigDecimal close, BigDecimal adjustedClose) { }

    public record PriceHistoryResponse(List<PricePoint> data,
                                       @JsonProperty("dataStatus") FreshnessStatus status,
                                       String source, LocalDate asOf, Instant retrievedAt, String message) { }

    public record DataEnvelope<T>(T data, FreshnessStatus status, String source,
                                  Instant asOf, Instant retrievedAt) { }

    public record SyncResponse(String symbol, int barsSaved, int splitsSaved,
                               FreshnessStatus status, Instant completedAt, String message) {
        public SyncResponse(String symbol, int barsSaved, int splitsSaved,
                            FreshnessStatus status, Instant completedAt) {
            this(symbol, barsSaved, splitsSaved, status, completedAt, null);
        }
    }

    public record ProviderStatus(String id, boolean configured) { }

    public record ProvidersResponse(List<ProviderStatus> providers, String primary, String fallback) { }
}
