package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionRecoveryService {
    private final JdbcTemplate jdbc;
    private final TransactionService transactions;
    private final PortfolioSnapshotInvalidator snapshots;
    private final PortfolioService portfolio;

    public TransactionRecoveryService(JdbcTemplate jdbc, TransactionService transactions,
                                      PortfolioSnapshotInvalidator snapshots, PortfolioService portfolio) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.snapshots = snapshots;
        this.portfolio = portfolio;
    }

    public record DeletedTransaction(UUID id, UUID transactionId, Instant deletedAt, String symbol,
                                     String type, LocalDate date, String quantity, String unitPrice,
                                     String amount, String fee) { }

    @Transactional(readOnly = true)
    public List<DeletedTransaction> list() {
        return jdbc.query("""
                SELECT a.*, i.symbol, a.record->>'transaction_type' AS type, a.record->>'trade_date' AS date,
                       a.record->>'quantity' AS quantity, a.record->>'unit_price' AS price,
                       a.record->>'amount' AS amount, a.record->>'fee' AS fee
                FROM transaction_recycle_bin a
                LEFT JOIN instrument i ON i.id = (a.record->>'instrument_id')::uuid
                WHERE a.restored_at IS NULL ORDER BY a.deleted_at DESC, a.id
                """, (row, index) -> {
            return new DeletedTransaction(row.getObject("id", UUID.class), row.getObject("transaction_id", UUID.class),
                    row.getTimestamp("deleted_at").toInstant(), row.getString("symbol"), row.getString("type"),
                    LocalDate.parse(row.getString("date")), row.getString("quantity"), row.getString("price"),
                    row.getString("amount"), row.getString("fee"));
        });
    }

    @Transactional
    public TransactionEntity restore(UUID archiveId) {
        var rows = jdbc.query("SELECT transaction_id FROM transaction_recycle_bin WHERE id = ? AND restored_at IS NULL FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), archiveId);
        if (rows.isEmpty()) throw new DomainException(HttpStatus.NOT_FOUND, "DELETED_TRANSACTION_NOT_FOUND",
                "Deleted transaction is unavailable or already restored");
        UUID transactionId = rows.getFirst();
        jdbc.update("""
                INSERT INTO investment_transaction
                SELECT (jsonb_populate_record(NULL::investment_transaction, record)).*
                FROM transaction_recycle_bin WHERE id = ?
                """, archiveId);
        transactions.validateLedger();
        TransactionEntity restored = transactions.get(transactionId);
        snapshots.invalidateFrom(restored.getTradeDate());
        portfolio.rebuildTodaySnapshot();
        jdbc.update("UPDATE transaction_recycle_bin SET restored_at = CURRENT_TIMESTAMP WHERE id = ?", archiveId);
        return restored;
    }
}
