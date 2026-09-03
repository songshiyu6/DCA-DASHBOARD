package com.dca.terminal.benchmark;

import com.dca.terminal.common.FreshnessStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class BenchmarkDtos {
    private BenchmarkDtos() { }

    public enum BenchmarkType {
        ETF,
        INDEX,
        EQUITY
    }

    public record SearchResult(String symbol, String name, String exchange, BenchmarkType type) { }

    public record PricePoint(LocalDate date, BigDecimal value) { }

    public record HistoryResponse(String symbol, String name, BenchmarkType type, String source,
                                  FreshnessStatus dataStatus, List<PricePoint> points) { }
}
