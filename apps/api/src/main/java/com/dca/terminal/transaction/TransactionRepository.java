package com.dca.terminal.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
    @Query("""
            select tx from TransactionEntity tx
            join fetch tx.instrument instrument
            where lower(instrument.symbol) = lower(coalesce(:symbol, instrument.symbol))
              and tx.tradeDate >= coalesce(:fromDate, tx.tradeDate)
              and tx.tradeDate <= coalesce(:toDate, tx.tradeDate)
            order by tx.tradeDate asc, tx.ledgerOrder asc, tx.id asc
            """)
    List<TransactionEntity> findForList(@Param("symbol") String symbol,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate);

    List<TransactionEntity> findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
    List<TransactionEntity> findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(LocalDate date);
    List<TransactionEntity> findAllByTradeDateBetweenOrderByTradeDateAscLedgerOrderAscIdAsc(LocalDate from, LocalDate to);
    List<TransactionEntity> findAllByInstrumentIdOrderByTradeDateAscLedgerOrderAscIdAsc(UUID instrumentId);
    List<TransactionEntity> findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(UUID planCycleId);
    Optional<TransactionEntity> findTopByOrderByLedgerOrderDesc();
    boolean existsByImportFingerprint(String fingerprint);
    boolean existsByContributionTypeAndContributionPlanIdAndTradeDateBetween(
            ContributionType contributionType, UUID contributionPlanId, LocalDate from, LocalDate to);

    @Query(value = "SELECT nextval('transaction_ledger_order_seq')", nativeQuery = true)
    long nextLedgerOrder();
}
