package com.dca.terminal.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CashLedgerCalculatorTest {
    @Test
    void replaysInternalAssetConversionsWithoutChangingNetExternalFlow() {
        LocalDate date = LocalDate.of(2026, 9, 1);
        TransactionEntity deposit = amount(TransactionType.DEPOSIT, date, "1000", 1L);
        TransactionEntity buy = trade(TransactionType.BUY, date, "2", "300", "5", 2L);
        TransactionEntity dividend = amount(TransactionType.DIVIDEND, date, "10", 3L);
        TransactionEntity interest = amount(TransactionType.INTEREST, date, "2", 4L);
        TransactionEntity sell = trade(TransactionType.SELL, date, "1", "250", "2", 5L);
        TransactionEntity fee = amount(TransactionType.FEE, date, "5", 6L);
        TransactionEntity withdrawal = amount(TransactionType.WITHDRAWAL, date, "100", 7L);

        CashLedgerCalculator.Calculation result = CashLedgerCalculator.calculate(
                List.of(deposit, buy, dividend, interest, sell, fee, withdrawal), date);

        assertDecimal("550", result.cashBalance());
        assertDecimal("900", result.netExternalFlow());
        assertDecimal("2", result.interestIncome());
    }

    @Test
    void permitsNegativeCashSoMissingFundingIsVisibleInsteadOfInvented() {
        TransactionEntity buy = trade(TransactionType.BUY, LocalDate.of(2026, 9, 1), "1", "100", "0", 1L);

        CashLedgerCalculator.Calculation result = CashLedgerCalculator.calculate(
                List.of(buy), LocalDate.of(2026, 9, 1));

        assertDecimal("-100", result.cashBalance());
        assertDecimal("0", result.netExternalFlow());
    }

    @Test
    void externalFlowExcludesDividendInterestFeesAndTrades() {
        LocalDate date = LocalDate.of(2026, 9, 1);
        List<TransactionEntity> transactions = List.of(
                amount(TransactionType.DEPOSIT, date, "500", 1L),
                trade(TransactionType.BUY, date, "1", "100", "0", 2L),
                amount(TransactionType.DIVIDEND, date, "20", 3L),
                amount(TransactionType.INTEREST, date, "3", 4L),
                amount(TransactionType.FEE, date, "2", 5L),
                amount(TransactionType.WITHDRAWAL, date, "50", 6L));

        BigDecimal external = transactions.stream().map(CashLedgerCalculator::externalFlowChange)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertDecimal("450", external);
    }

    private static TransactionEntity trade(TransactionType type, LocalDate date, String quantity,
                                           String price, String fee, long order) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setTransactionType(type);
        transaction.setTradeDate(date);
        transaction.setQuantity(new BigDecimal(quantity));
        transaction.setUnitPrice(new BigDecimal(price));
        transaction.setFee(new BigDecimal(fee));
        transaction.setLedgerOrder(order);
        return transaction;
    }

    private static TransactionEntity amount(TransactionType type, LocalDate date, String amount, long order) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setTransactionType(type);
        transaction.setTradeDate(date);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setFee(BigDecimal.ZERO);
        transaction.setLedgerOrder(order);
        return transaction;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
