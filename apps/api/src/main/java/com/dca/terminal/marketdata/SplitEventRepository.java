package com.dca.terminal.marketdata;

import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SplitEventRepository extends JpaRepository<SplitEventEntity, UUID> {
    List<SplitEventEntity> findAllByInstrumentIdAndEffectiveDateLessThanEqualOrderByEffectiveDateAsc(
            UUID instrumentId, LocalDate date);
    List<SplitEventEntity> findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
            Collection<UUID> instrumentIds, LocalDate date);
    Optional<SplitEventEntity> findByInstrumentIdAndEffectiveDate(UUID instrumentId, LocalDate date);
    Optional<SplitEventEntity> findByInstrumentIdAndEffectiveDateAndSource(
            UUID instrumentId, LocalDate date, String source);
}
