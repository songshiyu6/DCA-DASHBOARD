package com.dca.terminal.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
    List<TransactionEntity> findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
    List<TransactionEntity> findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(LocalDate date);
    List<TransactionEntity> findAllByTradeDateBetweenOrderByTradeDateAscLedgerOrderAscIdAsc(LocalDate from, LocalDate to);
    List<TransactionEntity> findAllByInstrumentIdOrderByTradeDateAscLedgerOrderAscIdAsc(UUID instrumentId);
    List<TransactionEntity> findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(UUID planCycleId);
    Optional<TransactionEntity> findTopByOrderByLedgerOrderDesc();
    boolean existsByImportFingerprint(String fingerprint);
}
