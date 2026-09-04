package com.dca.terminal.transaction;

import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.DomainException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Replays account cash from the immutable transaction ledger. Cash is never persisted as an
 * independently editable balance: every balance is a projection of ledger events.
 */
public final class CashLedgerCalculator {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    private CashLedgerCalculator() { }

    public static Calculation calculate(List<TransactionEntity> transactions, LocalDate asOf) {
        return replay(transactions).calculateThrough(asOf);
    }

    public static Replay replay(List<TransactionEntity> transactions) {
        return new Replay(transactions);
    }

    public static BigDecimal cashChange(TransactionEntity transaction) {
        if (transaction == null || transaction.getTransactionType() == null) {
            throw invalid("Transaction type is required for cash replay");
        }
        BigDecimal fee = DecimalMath.zeroIfNull(transaction.getFee());
        if (fee.signum() < 0) throw invalid("Transaction fee cannot be negative");
        return switch (transaction.getTransactionType()) {
            case BUY -> tradeNotional(transaction, "BUY").add(fee, MC).negate();
            case SELL -> tradeNotional(transaction, "SELL").subtract(fee, MC);
            case DIVIDEND -> nonNegativeAmount(transaction, "DIVIDEND");
            case FEE -> nonNegativeAmount(transaction, "FEE").negate();
            case DEPOSIT -> positiveAmount(transaction, "DEPOSIT");
            case WITHDRAWAL -> positiveAmount(transaction, "WITHDRAWAL").negate();
            case INTEREST -> positiveAmount(transaction, "INTEREST");
        };
    }

    public static BigDecimal externalFlowChange(TransactionEntity transaction) {
        if (transaction == null || transaction.getTransactionType() == null) {
            throw invalid("Transaction type is required for external cash-flow replay");
        }
        return switch (transaction.getTransactionType()) {
            case DEPOSIT -> positiveAmount(transaction, "DEPOSIT");
            case WITHDRAWAL -> positiveAmount(transaction, "WITHDRAWAL").negate();
            case BUY, SELL, DIVIDEND, FEE, INTEREST -> BigDecimal.ZERO;
        };
    }

    private static BigDecimal tradeNotional(TransactionEntity transaction, String label) {
        BigDecimal quantity = transaction.getQuantity();
        BigDecimal price = transaction.getUnitPrice();
        if (quantity == null || quantity.signum() <= 0 || price == null || price.signum() < 0) {
            throw invalid(label + " requires positive quantity and non-negative price");
        }
        return quantity.multiply(price, MC);
    }

    private static BigDecimal nonNegativeAmount(TransactionEntity transaction, String label) {
        BigDecimal amount = transaction.getAmount();
        if (amount == null || amount.signum() < 0) {
            throw invalid(label + " requires a non-negative amount");
        }
        return amount;
    }

    private static BigDecimal positiveAmount(TransactionEntity transaction, String label) {
        BigDecimal amount = transaction.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw invalid(label + " requires a positive amount");
        }
        return amount;
    }

    private static DomainException invalid(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", message);
    }

    public static final class Replay {
        private final List<TransactionEntity> orderedTransactions;
        private int transactionIndex;
        private LocalDate currentAsOf;
        private BigDecimal cashBalance = BigDecimal.ZERO;
        private BigDecimal netExternalFlow = BigDecimal.ZERO;
        private BigDecimal interestIncome = BigDecimal.ZERO;

        private Replay(List<TransactionEntity> transactions) {
            orderedTransactions = (transactions == null ? List.<TransactionEntity>of() : transactions).stream()
                    .sorted(Comparator.comparing(TransactionEntity::getTradeDate)
                            .thenComparing(TransactionEntity::getLedgerOrder, Comparator.nullsLast(Long::compareTo))
                            .thenComparing(TransactionEntity::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                            .thenComparing(transaction -> transaction.getId() == null ? "" : transaction.getId().toString()))
                    .toList();
        }

        public Calculation calculateThrough(LocalDate asOf) {
            if (asOf == null) throw new IllegalArgumentException("Valuation date is required");
            if (currentAsOf != null && asOf.isBefore(currentAsOf)) {
                throw new IllegalArgumentException("Replay dates must be non-decreasing");
            }
            while (transactionIndex < orderedTransactions.size()
                    && !orderedTransactions.get(transactionIndex).getTradeDate().isAfter(asOf)) {
                TransactionEntity transaction = orderedTransactions.get(transactionIndex++);
                cashBalance = cashBalance.add(cashChange(transaction), MC);
                netExternalFlow = netExternalFlow.add(externalFlowChange(transaction), MC);
                if (transaction.getTransactionType() == TransactionType.INTEREST) {
                    interestIncome = interestIncome.add(positiveAmount(transaction, "INTEREST"), MC);
                }
            }
            currentAsOf = asOf;
            return new Calculation(cashBalance, netExternalFlow, interestIncome);
        }
    }

    public record Calculation(BigDecimal cashBalance, BigDecimal netExternalFlow, BigDecimal interestIncome) { }
}
