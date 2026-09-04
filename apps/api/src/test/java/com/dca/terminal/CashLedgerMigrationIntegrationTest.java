package com.dca.terminal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@Testcontainers
class CashLedgerMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine");

    @Test
    void bridgesLegacyTradesIntoExplicitExternalFlowsWithoutChangingTheirEconomicMeaning() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("21")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.parse("2026-09-01T12:00:00Z");
        UUID instrumentId = UUID.randomUUID();
        UUID buyId = UUID.randomUUID();
        UUID sellId = UUID.randomUUID();
        UUID dividendId = UUID.randomUUID();

        insertInstrument(jdbc, instrumentId, now);
        insertTrade(jdbc, buyId, instrumentId, "BUY", "2", "100", "5", 1L, now);
        insertTrade(jdbc, sellId, instrumentId, "SELL", "1", "150", "2", 2L, now);
        insertAmount(jdbc, dividendId, instrumentId, "DIVIDEND", "10", 3L, now);
        jdbc.update("""
                INSERT INTO portfolio_snapshot_daily
                    (id, snapshot_date, market_value, cost_basis, net_cash_flow, realized_pl,
                     unrealized_pl, dividend_income, total_fees, data_status, created_at)
                VALUES (?, ?, 250, 100, 57, 48, 150, 10, 7, 'FRESH', ?)
                """, UUID.randomUUID(), LocalDate.of(2026, 9, 1), Timestamp.from(now));

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM investment_transaction WHERE transaction_type = 'DEPOSIT' AND amount = 205",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM investment_transaction WHERE transaction_type = 'WITHDRAWAL' AND amount = 148",
                Integer.class));
        assertEquals(57, jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE transaction_type WHEN 'DEPOSIT' THEN amount
                                    WHEN 'WITHDRAWAL' THEN -amount ELSE 0 END), 0)
                FROM investment_transaction
                """, BigDecimal.class).intValueExact());
        assertEquals(10, jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE
                    WHEN transaction_type = 'DEPOSIT' THEN amount
                    WHEN transaction_type = 'WITHDRAWAL' THEN -amount
                    WHEN transaction_type = 'BUY' THEN -((quantity * unit_price) + fee)
                    WHEN transaction_type = 'SELL' THEN (quantity * unit_price) - fee
                    WHEN transaction_type IN ('DIVIDEND', 'INTEREST') THEN amount
                    WHEN transaction_type = 'FEE' THEN -amount ELSE 0 END), 0)
                FROM investment_transaction
                """, BigDecimal.class).intValueExact());
        assertTrue(jdbc.queryForObject("""
                SELECT (SELECT ledger_order FROM investment_transaction
                        WHERE transaction_type = 'DEPOSIT' AND notes LIKE 'System-generated legacy cash bridge for BUY%')
                     < (SELECT ledger_order FROM investment_transaction WHERE id = ?)
                """, Boolean.class, buyId));
        assertTrue(jdbc.queryForObject("""
                SELECT (SELECT ledger_order FROM investment_transaction
                        WHERE transaction_type = 'WITHDRAWAL' AND notes LIKE 'System-generated legacy cash bridge for SELL%')
                     > (SELECT ledger_order FROM investment_transaction WHERE id = ?)
                """, Boolean.class, sellId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM portfolio_snapshot_daily", Integer.class));
        assertTrue(jdbc.queryForObject("""
                SELECT is_nullable = 'YES' FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'investment_transaction' AND column_name = 'instrument_id'
                """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
                SELECT count(*) = 2 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'portfolio_snapshot_daily'
                  AND column_name IN ('securities_value', 'cash_balance')
                """, Boolean.class));
        long maxOrder = jdbc.queryForObject("SELECT max(ledger_order) FROM investment_transaction", Long.class);
        long nextOrder = jdbc.queryForObject("SELECT nextval('transaction_ledger_order_seq')", Long.class);
        assertTrue(nextOrder > maxOrder);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO investment_transaction
                    (id, instrument_id, transaction_type, trade_date, quantity, unit_price, fee,
                     currency, ledger_order, created_at, updated_at)
                VALUES (?, NULL, 'BUY', ?, 1, 100, 0, 'USD', nextval('transaction_ledger_order_seq'), ?, ?)
                """, UUID.randomUUID(), LocalDate.of(2026, 9, 2), Timestamp.from(now), Timestamp.from(now)));
    }

    @Test
    void carriesLegacyDcaOwnershipOntoTheSyntheticFundingDeposit() {
        String schema = "cash_dca_" + UUID.randomUUID().toString().replace("-", "");
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("21")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.parse("2026-09-01T12:00:00Z");
        UUID instrumentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID buyId = UUID.randomUUID();
        insertInstrument(jdbc, instrumentId, now);
        jdbc.update("""
                INSERT INTO investment_plan
                    (id, name, currency, frequency, monthly_budget, start_date,
                     execution_start_day, execution_end_day, status, created_at, updated_at)
                VALUES (?, 'Core', 'USD', 'MONTHLY', 1000, ?, 1, 31, 'ACTIVE', ?, ?)
                """, planId, LocalDate.of(2026, 1, 1), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO investment_plan_cycle
                    (id, plan_id, period, planned_amount, executed_amount, status, created_at, updated_at)
                VALUES (?, ?, '2026-09', 1000, 0, 'OPEN', ?, ?)
                """, cycleId, planId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO investment_transaction
                    (id, instrument_id, plan_cycle_id, contribution_type, transaction_type, trade_date,
                     quantity, unit_price, fee, currency, ledger_order, created_at, updated_at)
                VALUES (?, ?, ?, 'DCA', 'BUY', ?, 1, 100, 0, 'USD', 1, ?, ?)
                """, buyId, instrumentId, cycleId, LocalDate.of(2026, 9, 1), Timestamp.from(now), Timestamp.from(now));

        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

        assertEquals("DCA", jdbc.queryForObject("""
                SELECT contribution_type FROM investment_transaction
                WHERE transaction_type = 'DEPOSIT' AND notes LIKE 'System-generated legacy cash bridge for BUY%'
                """, String.class));
        assertEquals(planId, jdbc.queryForObject("""
                SELECT contribution_plan_id FROM investment_transaction
                WHERE transaction_type = 'DEPOSIT' AND notes LIKE 'System-generated legacy cash bridge for BUY%'
                """, UUID.class));
        assertEquals("DCA", jdbc.queryForObject(
                "SELECT contribution_type FROM investment_transaction WHERE id = ?", String.class, buyId));
    }

    private static void insertInstrument(JdbcTemplate jdbc, UUID instrumentId, Instant now) {
        jdbc.update("""
                INSERT INTO instrument
                    (id, symbol, name, currency, instrument_type, tracked, data_status, created_at, updated_at)
                VALUES (?, ?, 'Cash migration test ETF', 'USD', 'ETF', TRUE,
                    'INSUFFICIENT_HISTORY', ?, ?)
                """, instrumentId, "S" + instrumentId.toString().replace("-", "").substring(0, 10),
                Timestamp.from(now), Timestamp.from(now));
    }

    private static void insertTrade(JdbcTemplate jdbc, UUID id, UUID instrumentId, String type,
                                    String quantity, String price, String fee, long ledgerOrder, Instant now) {
        jdbc.update("""
                INSERT INTO investment_transaction
                    (id, instrument_id, transaction_type, trade_date, quantity, unit_price, fee,
                     currency, ledger_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'USD', ?, ?, ?)
                """, id, instrumentId, type, LocalDate.of(2026, 9, 1), new BigDecimal(quantity),
                new BigDecimal(price), new BigDecimal(fee), ledgerOrder, Timestamp.from(now), Timestamp.from(now));
    }

    private static void insertAmount(JdbcTemplate jdbc, UUID id, UUID instrumentId, String type,
                                     String amount, long ledgerOrder, Instant now) {
        jdbc.update("""
                INSERT INTO investment_transaction
                    (id, instrument_id, transaction_type, trade_date, amount, fee,
                     currency, ledger_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, 'USD', ?, ?, ?)
                """, id, instrumentId, type, LocalDate.of(2026, 9, 1), new BigDecimal(amount),
                ledgerOrder, Timestamp.from(now), Timestamp.from(now));
    }
}
