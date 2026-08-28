package com.dca.terminal;

import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("postgres")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class PostgresSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    InstrumentRepository instrumentRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("dca.scheduler.enabled", () -> false);
        registry.add("dca.security.enabled", () -> true);
    }

    @Test
    void startsWithFlywayMigrationsAndHibernateValidation() {
        assertTrue(POSTGRES.isRunning());
    }

    @Test
    void allocatesLedgerOrdersAtomicallyAcrossConcurrentCallers() throws Exception {
        int callers = 8;
        int allocationsPerCaller = 20;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                for (int allocation = 0; allocation < allocationsPerCaller; allocation++) {
                    futures.add(executor.submit(() -> jdbcTemplate.queryForObject(
                            "SELECT nextval('transaction_ledger_order_seq')", Long.class)));
                }
            }

            Set<Long> allocated = new HashSet<>();
            for (Future<Long> future : futures) allocated.add(future.get());

            assertEquals(callers * allocationsPerCaller, allocated.size());
            assertTrue(allocated.stream().allMatch(order -> order > 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void keepsTheLedgerOrderUniqueIndexAndAppliesListFiltersInTheRepository() {
        String matchingSymbol = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String otherSymbol = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        InstrumentEntity matchingInstrument = instrumentRepository.saveAndFlush(instrument(matchingSymbol));
        InstrumentEntity otherInstrument = instrumentRepository.saveAndFlush(instrument(otherSymbol));
        TransactionEntity inRange = transaction(matchingInstrument, LocalDate.of(2026, 8, 5));
        TransactionEntity outsideRange = transaction(matchingInstrument, LocalDate.of(2026, 9, 5));
        TransactionEntity otherSymbolTransaction = transaction(otherInstrument, LocalDate.of(2026, 8, 5));
        inRange.setLedgerOrder(transactionRepository.nextLedgerOrder());
        outsideRange.setLedgerOrder(transactionRepository.nextLedgerOrder());
        otherSymbolTransaction.setLedgerOrder(transactionRepository.nextLedgerOrder());
        transactionRepository.saveAll(List.of(inRange, outsideRange, otherSymbolTransaction));
        transactionRepository.flush();

        try {
            Boolean indexExists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'uq_transaction_ledger_order')", Boolean.class);
            List<TransactionEntity> result = transactionRepository.findForList(
                    matchingSymbol.toLowerCase(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

            assertTrue(indexExists);
            assertEquals(1, result.size());
            assertEquals(inRange.getId(), result.getFirst().getId());
        } finally {
            transactionRepository.deleteAllById(List.of(inRange.getId(), outsideRange.getId(), otherSymbolTransaction.getId()));
            transactionRepository.flush();
            instrumentRepository.deleteAllById(List.of(matchingInstrument.getId(), otherInstrument.getId()));
            instrumentRepository.flush();
        }
    }

    private static InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(symbol);
        instrument.setName(symbol + " ETF");
        return instrument;
    }

    private static TransactionEntity transaction(InstrumentEntity instrument, LocalDate date) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setInstrument(instrument);
        transaction.setTransactionType(TransactionType.BUY);
        transaction.setTradeDate(date);
        transaction.setQuantity(BigDecimal.ONE);
        transaction.setUnitPrice(new BigDecimal("100"));
        transaction.setFee(BigDecimal.ZERO);
        return transaction;
    }
}
