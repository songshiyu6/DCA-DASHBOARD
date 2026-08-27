package com.dca.terminal.marketdata;

import com.dca.terminal.marketdata.MarketDataEntities.PriceDailyEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceDailyRepository extends JpaRepository<PriceDailyEntity, UUID> {
    List<PriceDailyEntity> findAllByInstrumentIdAndTradeDateBetweenOrderByTradeDateAsc(
            UUID instrumentId, LocalDate from, LocalDate to);
    List<PriceDailyEntity> findAllByInstrumentIdAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
            UUID instrumentId, LocalDate from);
    List<PriceDailyEntity> findAllByInstrumentIdInAndTradeDateBetweenOrderByInstrumentIdAscTradeDateAsc(
            Collection<UUID> instrumentIds, LocalDate from, LocalDate to);
    List<PriceDailyEntity> findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
            Collection<UUID> instrumentIds, LocalDate to);
    Optional<PriceDailyEntity> findTopByInstrumentIdOrderByTradeDateDesc(UUID instrumentId);
    Optional<PriceDailyEntity> findTopByInstrumentIdAndTradeDateLessThanEqualOrderByTradeDateDesc(
            UUID instrumentId, LocalDate date);
    Optional<PriceDailyEntity> findByInstrumentIdAndTradeDateAndSource(
            UUID instrumentId, LocalDate date, String source);
}
