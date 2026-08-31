package com.dca.terminal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
class ContributionMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine");

    @Test
    void upgradesV016LegacyCycleBuysAndEnforcesTheContributionContract() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("16")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        UUID instrumentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID legacyDcaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        jdbc.update("""
                INSERT INTO instrument
                    (id, symbol, name, currency, instrument_type, tracked, data_status, created_at, updated_at)
                VALUES (?, 'VOO', 'Vanguard S&P 500 ETF', 'USD', 'ETF', TRUE,
                    'INSUFFICIENT_HISTORY', ?, ?)
                """, instrumentId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO investment_plan
                    (id, name, currency, frequency, monthly_budget, start_date,
                     execution_start_day, execution_end_day, status, created_at, updated_at)
                VALUES (?, 'Core', 'USD', 'MONTHLY', 1000, ?, 1, 31, 'ACTIVE', ?, ?)
                """, planId, LocalDate.of(2026, 1, 1), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO investment_plan_cycle
                    (id, plan_id, period, planned_amount, executed_amount, status, created_at, updated_at)
                VALUES (?, ?, '2026-08', 1000, 0, 'OPEN', ?, ?)
                """, cycleId, planId, Timestamp.from(now), Timestamp.from(now));
        insertTransaction(jdbc, legacyDcaId, instrumentId, cycleId, "BUY", null, null, 1L, now);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertEquals("DCA", jdbc.queryForObject(
                "SELECT contribution_type FROM investment_transaction WHERE id = ?", String.class, legacyDcaId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM investment_transaction WHERE id = ? AND contribution_plan_id IS NOT NULL",
                Integer.class, legacyDcaId));
        assertTrue(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_constraint
                    WHERE conname = 'chk_investment_transaction_contribution_source_consistency'
                )
                """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_indexes
                    WHERE indexname = 'idx_investment_transaction_unclassified_buy'
                )
                """, Boolean.class));
        assertTrue(jdbc.queryForObject("SELECT to_regclass('contribution_classification_audit') IS NOT NULL",
                Boolean.class));

        assertThrows(DataIntegrityViolationException.class, () -> insertTransaction(jdbc, UUID.randomUUID(),
                instrumentId, null, "SELL", "INITIAL", planId, 2L, now));
        assertThrows(DataIntegrityViolationException.class, () -> insertTransaction(jdbc, UUID.randomUUID(),
                instrumentId, null, "BUY", "DCA", null, 3L, now));
        assertThrows(DataIntegrityViolationException.class, () -> insertTransaction(jdbc, UUID.randomUUID(),
                instrumentId, cycleId, "BUY", "INITIAL", planId, 4L, now));
        assertThrows(DataIntegrityViolationException.class, () -> insertTransaction(jdbc, UUID.randomUUID(),
                instrumentId, null, "BUY", "UNPLANNED", planId, 5L, now));

        insertTransaction(jdbc, UUID.randomUUID(), instrumentId, null, "BUY", null, null, 6L, now);
        insertTransaction(jdbc, UUID.randomUUID(), instrumentId, null, "BUY", "INITIAL", planId, 7L, now);
        insertTransaction(jdbc, UUID.randomUUID(), instrumentId, null, "BUY", "UNPLANNED", null, 8L, now);
    }

    @Test
    void abortsV017BeforeAddingConstraintsWhenLegacyRowsAreAmbiguous() {
        String schema = "invalid_" + UUID.randomUUID().toString().replace("-", "");
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("16")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        UUID instrumentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID invalidTransactionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        jdbc.update("""
                INSERT INTO instrument
                    (id, symbol, name, currency, instrument_type, tracked, data_status, created_at, updated_at)
                VALUES (?, 'VOO', 'Vanguard S&P 500 ETF', 'USD', 'ETF', TRUE,
                    'INSUFFICIENT_HISTORY', ?, ?)
                """, instrumentId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO investment_plan
                    (id, name, currency, frequency, monthly_budget, start_date,
                     execution_start_day, execution_end_day, status, created_at, updated_at)
                VALUES (?, 'Core', 'USD', 'MONTHLY', 1000, ?, 1, 31, 'ACTIVE', ?, ?)
                """, planId, LocalDate.of(2026, 1, 1), Timestamp.from(now), Timestamp.from(now));
        insertTransaction(jdbc, invalidTransactionId, instrumentId, null,
                "SELL", "INITIAL", planId, 1L, now);

        FlywayException exception = assertThrows(FlywayException.class, () -> Flyway.configure()
                .dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate());

        assertTrue(causeMessages(exception).contains("Contribution source audit failed"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'chk_investment_transaction_contribution_source_consistency'
                """, Integer.class));
        assertTrue(jdbc.queryForObject(
                "SELECT to_regclass('contribution_classification_audit') IS NULL", Boolean.class));
    }

    private static void insertTransaction(JdbcTemplate jdbc, UUID id, UUID instrumentId, UUID cycleId,
                                          String transactionType, String contributionType,
                                          UUID contributionPlanId, long ledgerOrder, Instant now) {
        jdbc.update("""
                INSERT INTO investment_transaction
                    (id, instrument_id, plan_cycle_id, transaction_type, trade_date, quantity,
                     unit_price, fee, currency, contribution_type, contribution_plan_id,
                     ledger_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 100, 0, 'USD', ?, ?, ?, ?, ?)
                """, id, instrumentId, cycleId, transactionType, LocalDate.of(2026, 8, 1),
                contributionType, contributionPlanId, ledgerOrder, Timestamp.from(now), Timestamp.from(now));
    }

    private static String causeMessages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
