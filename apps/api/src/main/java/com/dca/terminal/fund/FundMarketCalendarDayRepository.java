package com.dca.terminal.fund;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundMarketCalendarDayRepository extends JpaRepository<FundMarketCalendarDayEntity, UUID> {
    Optional<FundMarketCalendarDayEntity> findByCalendarCodeAndMarketDate(String calendarCode, LocalDate marketDate);
    List<FundMarketCalendarDayEntity> findAllByCalendarCodeAndMarketDateBetweenOrderByMarketDateAsc(
            String calendarCode, LocalDate startDate, LocalDate endDate);
}
