package com.dca.terminal.marketdata;

import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundNavDailyRepository extends JpaRepository<FundNavDailyEntity, UUID> {
    Optional<FundNavDailyEntity> findTopByInstrumentIdOrderByNavDateDesc(UUID instrumentId);
    Optional<FundNavDailyEntity> findByInstrumentIdAndNavDateAndSource(
            UUID instrumentId, LocalDate date, String source);
    List<FundNavDailyEntity> findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(UUID instrumentId);
    long deleteAllByInstrumentId(UUID instrumentId);
}
