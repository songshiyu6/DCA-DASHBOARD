package com.dca.terminal.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ChinaFundDataProvider {
    record NavPoint(LocalDate date, BigDecimal nav) { }

    String source();

    List<NavPoint> navHistory(String fundCode, LocalDate startDate, LocalDate endDate);

    List<LocalDate> tradingDays(LocalDate startDate, LocalDate endDate);
}
