package com.dca.terminal.instrument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<InstrumentEntity, UUID> {
    Optional<InstrumentEntity> findBySymbolIgnoreCase(String symbol);
    List<InstrumentEntity> findAllByTrackedTrueOrderBySymbolAsc();
    List<InstrumentEntity> findAllBySymbolContainingIgnoreCaseOrderBySymbolAsc(String query);
}
