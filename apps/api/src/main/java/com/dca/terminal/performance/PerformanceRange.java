package com.dca.terminal.performance;

import com.dca.terminal.common.DomainException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;

public enum PerformanceRange {
    ONE_MONTH("1M"),
    THREE_MONTHS("3M"),
    ONE_YEAR("1Y"),
    YEAR_TO_DATE("YTD"),
    ALL("ALL");

    private final String code;

    PerformanceRange(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public LocalDate startDate(LocalDate end) {
        return switch (this) {
            case ONE_MONTH -> end.minusMonths(1);
            case THREE_MONTHS -> end.minusMonths(3);
            case ONE_YEAR -> end.minusYears(1);
            case YEAR_TO_DATE -> LocalDate.of(end.getYear(), 1, 1);
            case ALL -> null;
        };
    }

    public static PerformanceRange parse(String value) {
        String normalized = value == null || value.isBlank() ? "1Y" : value.trim().toUpperCase();
        for (PerformanceRange range : values()) {
            if (range.code.equals(normalized)) return range;
        }
        throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PERFORMANCE_RANGE",
                "Performance range must be one of 1M, 3M, 1Y, YTD, ALL");
    }
}
