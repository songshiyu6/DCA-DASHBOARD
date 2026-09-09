package com.dca.terminal.fund;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundPurchaseRepository extends JpaRepository<FundPurchaseEntity, UUID> {
    List<FundPurchaseEntity> findAllByInstrumentIdOrderByPurchaseDateAscCreatedAtAscIdAsc(UUID instrumentId);
    List<FundPurchaseEntity> findAllByOrderByPurchaseDateAscCreatedAtAscIdAsc();
    Optional<FundPurchaseEntity> findByIdAndInstrumentId(UUID id, UUID instrumentId);
    long deleteAllByInstrumentId(UUID instrumentId);
}
