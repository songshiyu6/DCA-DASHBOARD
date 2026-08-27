package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FifoCalculatorTest {

    @Test
    void consumesOldestLotsAndIncludesExecutionFeesInBasisAndProceeds() {
        InstrumentEntity instrument = instrument("VOO");
        TransactionEntity firstBuy = transaction(instrument, TransactionType.BUY, "2026-01-05", "2", "100", "2");
        TransactionEntity secondBuy = transaction(instrument, TransactionType.BUY, "2026-02-05", "1", "120", "0");
        TransactionEntity sell = transaction(instrument, TransactionType.SELL, "2026-03-05", "1", "150", "3");

        FifoCalculator.Calculation calculation = FifoCalculator.calculate(
                List.of(firstBuy, secondBuy, sell), new HashMap<>(), LocalDate.of(2026, 3, 31));

        FifoCalculator.Position position = calculation.positions().get(0);
        assertDecimal("2", position.shares());
        assertDecimal("221", position.costBasis());
        assertDecimal("46", calculation.realized());
        assertDecimal("5", calculation.totalFees());
    }

    @Test
    void appliesSplitBeforeSellingAndPreservesEconomicCost() {
        InstrumentEntity instrument = instrument("QQQ");
        TransactionEntity buy = transaction(instrument, TransactionType.BUY, "2026-01-05", "1", "100", "0");
        TransactionEntity sell = transaction(instrument, TransactionType.SELL, "2026-02-11", "1", "60", "0");
        SplitEventEntity split = new SplitEventEntity();
        split.setInstrument(instrument);
        split.setEffectiveDate(LocalDate.of(2026, 2, 10));
        split.setNumerator(new BigDecimal("2"));
        split.setDenominator(BigDecimal.ONE);

        Map<java.util.UUID, List<SplitEventEntity>> splits = new HashMap<>();
        splits.put(instrument.getId(), List.of(split));
        FifoCalculator.Calculation calculation = FifoCalculator.calculate(
                List.of(buy, sell), splits, LocalDate.of(2026, 2, 28));

        FifoCalculator.Position position = calculation.positions().get(0);
        assertDecimal("1", position.shares());
        assertDecimal("50", position.costBasis());
        assertDecimal("10", calculation.realized());
    }

    @Test
    void usesLedgerOrderForMultipleSameDayLotsEvenWhenInputOrderDiffers() {
        InstrumentEntity instrument = instrument("VOO");
        TransactionEntity firstBuy = transaction(instrument, TransactionType.BUY, "2026-03-05", "1", "100", "0");
        firstBuy.setLedgerOrder(10L);
        TransactionEntity secondBuy = transaction(instrument, TransactionType.BUY, "2026-03-05", "1", "200", "0");
        secondBuy.setLedgerOrder(11L);
        TransactionEntity sell = transaction(instrument, TransactionType.SELL, "2026-03-05", "1", "150", "0");
        sell.setLedgerOrder(12L);

        FifoCalculator.Calculation calculation = FifoCalculator.calculate(
                List.of(sell, secondBuy, firstBuy), Map.of(), LocalDate.of(2026, 3, 5));

        assertDecimal("1", calculation.positions().getFirst().shares());
        assertDecimal("200", calculation.positions().getFirst().costBasis());
        assertDecimal("50", calculation.realized());
    }

    @Test
    void appliesOnlyOneSplitWhenProvidersReportTheSameCorporateAction() {
        InstrumentEntity instrument = instrumentWithId("QQQ");
        TransactionEntity buy = transaction(instrument, TransactionType.BUY, "2026-01-05", "1", "100", "0");
        buy.setLedgerOrder(1L);
        TransactionEntity sell = transaction(instrument, TransactionType.SELL, "2026-02-11", "1", "60", "0");
        sell.setLedgerOrder(2L);
        SplitEventEntity yahoo = split(instrument, "2026-02-10", "2", "1", "YAHOO");
        SplitEventEntity twelveData = split(instrument, "2026-02-10", "2", "1", "TWELVE_DATA");

        Map<java.util.UUID, List<SplitEventEntity>> splits = new HashMap<>();
        splits.put(instrument.getId(), List.of(twelveData, yahoo));
        FifoCalculator.Calculation calculation = FifoCalculator.calculate(
                List.of(buy, sell), splits, LocalDate.of(2026, 2, 28));

        assertDecimal("1", calculation.positions().getFirst().shares());
        assertDecimal("50", calculation.positions().getFirst().costBasis());
        assertDecimal("10", calculation.realized());
    }

    @Test
    void countsStandaloneFeesOnceAndRejectsASecondFeeField() {
        InstrumentEntity instrument = instrument("SCHD");
        TransactionEntity dividend = transaction(instrument, TransactionType.DIVIDEND, "2026-01-05", null, null, "0");
        dividend.setAmount(new BigDecimal("25"));
        TransactionEntity standaloneFee = transaction(instrument, TransactionType.FEE, "2026-01-06", null, null, "0");
        standaloneFee.setAmount(new BigDecimal("3"));

        FifoCalculator.Calculation calculation = FifoCalculator.calculate(
                List.of(dividend, standaloneFee), Map.of(), LocalDate.of(2026, 1, 31));

        assertDecimal("25", calculation.dividends());
        assertDecimal("3", calculation.standaloneFees());
        assertDecimal("3", calculation.totalFees());

        dividend.setFee(new BigDecimal("1"));
        DomainException exception = assertThrows(DomainException.class,
                () -> FifoCalculator.calculate(List.of(dividend), Map.of(), LocalDate.of(2026, 1, 31)));
        assertEquals("INVALID_TRANSACTION", exception.code());
    }

    private static InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(symbol);
        instrument.setName(symbol + " ETF");
        return instrument;
    }

    private static InstrumentEntity instrumentWithId(String symbol) {
        InstrumentEntity instrument = org.mockito.Mockito.mock(InstrumentEntity.class);
        org.mockito.Mockito.when(instrument.getId()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(instrument.getSymbol()).thenReturn(symbol);
        return instrument;
    }

    private static TransactionEntity transaction(InstrumentEntity instrument, TransactionType type, String date,
                                                 String quantity, String price, String fee) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setInstrument(instrument);
        transaction.setTransactionType(type);
        transaction.setTradeDate(LocalDate.parse(date));
        transaction.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        transaction.setUnitPrice(price == null ? null : new BigDecimal(price));
        transaction.setFee(new BigDecimal(fee));
        return transaction;
    }

    private static SplitEventEntity split(InstrumentEntity instrument, String date, String numerator,
                                          String denominator, String source) {
        SplitEventEntity split = new SplitEventEntity();
        split.setInstrument(instrument);
        split.setEffectiveDate(LocalDate.parse(date));
        split.setNumerator(new BigDecimal(numerator));
        split.setDenominator(new BigDecimal(denominator));
        split.setSource(source);
        return split;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
