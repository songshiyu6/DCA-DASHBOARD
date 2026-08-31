package com.dca.terminal.instrument;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.marketdata.QuoteSession;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class InstrumentDtos {
    private InstrumentDtos() { }

    public record InstrumentResponse(
            UUID id, String symbol, String name, String exchange, String currency,
            @JsonProperty("instrumentType") InstrumentType type, String issuer, BigDecimal expenseRatio, BigDecimal aum,
            BigDecimal dividendYield, String dataProvider, boolean tracked,
            FreshnessStatus dataStatus) { }

    public record AddInstrumentRequest(@JsonAlias("ticker") @NotBlank String symbol) { }

    public record SearchResult(String symbol, String name, String exchange, String currency,
                               @JsonProperty("instrumentType") InstrumentType type) { }

    public record QuoteResponse(
            String symbol, BigDecimal price, BigDecimal previousClose, BigDecimal change,
            BigDecimal changePercent, BigDecimal bid, BigDecimal ask, Instant marketTimestamp,
            Instant retrievedAt, String source, FreshnessStatus status, QuoteSession quoteSession,
            BigDecimal nav, LocalDate navDate) { }
}
