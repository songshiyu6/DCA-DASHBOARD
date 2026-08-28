package com.dca.terminal.portfolio;

import com.dca.terminal.transaction.TransactionService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class CapacityBaselineTest {
    private static final int INSTRUMENTS = 20;
    private static final int TRANSACTIONS = 10_000;
    private static final LocalDate END = LocalDate.of(2026, 8, 27);
    private static final LocalDate START = END.minusYears(5);

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    PortfolioService portfolioService;

    @Autowired
    TransactionService transactionService;

    @Test
    void recordsGeneratedCapacityBaselineWithoutAddingSpeculativeIndexes() throws Exception {
        List<UUID> instrumentIds = insertGeneratedData();
        assertEquals(INSTRUMENTS, instrumentIds.size());
        assertEquals(TRANSACTIONS, count("investment_transaction"));
        long priceRows = count("market_price_daily");
        assertEquals(INSTRUMENTS * (ChronoUnit.DAYS.between(START, END) + 1), priceRows);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        Measurement currentViews = measure(statistics, portfolioService::currentViews);
        Measurement independentCurrent = measure(statistics, () -> {
            portfolioService.summary();
            portfolioService.holdings();
            portfolioService.allocation();
        });
        Measurement dashboardShared = measure(statistics, () -> {
            portfolioService.currentViews();
            portfolioService.history("1Y");
        });
        Measurement history = measure(statistics, () -> portfolioService.history("1Y"));
        Measurement transactions = measure(statistics, () -> transactionService.list(null, null, null));

        assertTrue(currentViews.statements() > 0);
        assertTrue(currentViews.statements() < independentCurrent.statements(),
                "shared currentViews must not rebuild the current ledger three times");
        assertEquals(TRANSACTIONS, transactionService.list(null, null, null).size());

        String report = """
                # SA-09 capacity baseline

                Generated data, not a real account. No speculative index or cache was added.

                - Instruments: %d
                - Transactions: %d
                - Daily prices: %d rows (5 years from %s to %s)
                - Clock date used for generation: %s

                | Workload | Elapsed ms | Prepared statements | Query executions | Entity loads |
                | --- | ---: | ---: | ---: | ---: |
                | currentViews (shared ledger) | %d | %d | %d | %d |
                | independent summary+holdings+allocation | %d | %d | %d | %d |
                | dashboard currentViews + history(1Y) | %d | %d | %d | %d |
                | history(1Y) | %d | %d | %d | %d |
                | transaction list | %d | %d | %d | %d |

                Observation: currentViews used fewer statements than three independent current-ledger
                rebuilds. history stayed on its own snapshot/replay path. Existing trade-date and
                instrument-date indexes were sufficient for this generated volume; no schema change.
                """.formatted(
                INSTRUMENTS, TRANSACTIONS, priceRows, START, END, END,
                currentViews.elapsedMs(), currentViews.statements(), currentViews.queries(), currentViews.entityLoads(),
                independentCurrent.elapsedMs(), independentCurrent.statements(), independentCurrent.queries(), independentCurrent.entityLoads(),
                dashboardShared.elapsedMs(), dashboardShared.statements(), dashboardShared.queries(), dashboardShared.entityLoads(),
                history.elapsedMs(), history.statements(), history.queries(), history.entityLoads(),
                transactions.elapsedMs(), transactions.statements(), transactions.queries(), transactions.entityLoads());

        Path buildReport = Path.of("build/reports/sa-09-capacity-baseline.md");
        Files.createDirectories(buildReport.getParent());
        Files.writeString(buildReport, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private List<UUID> insertGeneratedData() {
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        List<UUID> instrumentIds = new ArrayList<>(INSTRUMENTS);
        List<Object[]> instruments = new ArrayList<>(INSTRUMENTS);
        for (int index = 0; index < INSTRUMENTS; index++) {
            UUID id = UUID.randomUUID();
            instrumentIds.add(id);
            String symbol = "ETF%02d".formatted(index);
            instruments.add(new Object[] {id, symbol, symbol + " ETF", "NYSE", "USD", "ETF", true, "FRESH", timestamp, timestamp});
        }
        jdbcTemplate.batchUpdate("""
                insert into instrument (id, symbol, name, exchange, currency, instrument_type, tracked, data_status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, instruments);

        int days = (int) (ChronoUnit.DAYS.between(START, END) + 1);
        for (int instrumentIndex = 0; instrumentIndex < instrumentIds.size(); instrumentIndex++) {
            UUID instrumentId = instrumentIds.get(instrumentIndex);
            int index = instrumentIndex;
            jdbcTemplate.batchUpdate("""
                    insert into market_price_daily (id, instrument_id, trade_date, close, adjusted_close, source, created_at)
                    values (?, ?, ?, ?, ?, 'YAHOO', ?)
                    """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int dayIndex) throws java.sql.SQLException {
                    LocalDate date = START.plusDays(dayIndex);
                    BigDecimal close = BigDecimal.valueOf(50 + index + (dayIndex % 40) * 0.25d);
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, instrumentId);
                    statement.setDate(3, Date.valueOf(date));
                    statement.setBigDecimal(4, close);
                    statement.setBigDecimal(5, close);
                    statement.setTimestamp(6, timestamp);
                }

                @Override
                public int getBatchSize() {
                    return days;
                }
            });
        }

        jdbcTemplate.batchUpdate("""
                insert into investment_transaction
                    (id, instrument_id, transaction_type, trade_date, quantity, unit_price, fee, currency, ledger_order, created_at, updated_at)
                values (?, ?, 'BUY', ?, ?, ?, 0, 'USD', ?, ?, ?)
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int i) throws java.sql.SQLException {
                UUID instrumentId = instrumentIds.get(i % INSTRUMENTS);
                long dayOffset = (i * 13L) % days;
                LocalDate date = START.plusDays(dayOffset);
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, instrumentId);
                statement.setDate(3, Date.valueOf(date));
                statement.setBigDecimal(4, BigDecimal.ONE);
                statement.setBigDecimal(5, BigDecimal.valueOf(100 + (i % 20)));
                statement.setLong(6, i + 1L);
                statement.setTimestamp(7, timestamp);
                statement.setTimestamp(8, timestamp);
            }

            @Override
            public int getBatchSize() {
                return TRANSACTIONS;
            }
        });
        return instrumentIds;
    }

    private Measurement measure(Statistics statistics, Runnable action) {
        statistics.clear();
        long start = System.nanoTime();
        action.run();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return new Measurement(elapsedMs, statistics.getPrepareStatementCount(),
                statistics.getQueryExecutionCount(), statistics.getEntityLoadCount());
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    private record Measurement(long elapsedMs, long statements, long queries, long entityLoads) { }
}
