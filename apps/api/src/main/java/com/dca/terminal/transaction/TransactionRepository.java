package com.dca.terminal.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
    @Query("""
            select tx from TransactionEntity tx
            left join fetch tx.instrument instrument
            where (:symbol is null or lower(instrument.symbol) = lower(:symbol))
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

    // Historical product semantics deliberately key the initial-capital month off an actual INITIAL BUY,
    // not merely an INITIAL DEPOSIT that has not yet been invested. Keep the existing method signature so
    // PlanService and its mocks remain stable while cash-source rows become first-class ledger events.
    @Query("""
            select (count(tx) > 0) from TransactionEntity tx
            where tx.transactionType = com.dca.terminal.transaction.TransactionType.BUY
              and tx.contributionType = :contributionType
              and tx.contributionPlanId = :contributionPlanId
              and tx.tradeDate between :fromDate and :toDate
            """)
    boolean existsByContributionTypeAndContributionPlanIdAndTradeDateBetween(
            @Param("contributionType") ContributionType contributionType,
            @Param("contributionPlanId") UUID contributionPlanId,
            @Param("fromDate") LocalDate from,
            @Param("toDate") LocalDate to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select tx from TransactionEntity tx
            left join fetch tx.instrument
            where tx.id in :ids
            """)
    List<TransactionEntity> findAllByIdInForUpdate(@Param("ids") List<UUID> ids);

    @Query(value = "SELECT nextval('transaction_ledger_order_seq')", nativeQuery = true)
    long nextLedgerOrder();
}
