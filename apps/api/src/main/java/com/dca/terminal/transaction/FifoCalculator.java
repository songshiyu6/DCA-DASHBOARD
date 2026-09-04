package com.dca.terminal.transaction;

import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.marketdata.ProviderPriority;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class FifoCalculator {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    private FifoCalculator() { }

    public static Calculation calculate(List<TransactionEntity> transactions,
                                        Map<UUID, List<SplitEventEntity>> splits,
                                        LocalDate asOf) {
        return calculate(transactions, splits, asOf, List.of());
    }

    public static Calculation calculate(List<TransactionEntity> transactions,
                                        Map<UUID, List<SplitEventEntity>> splits,
                                        LocalDate asOf,
                                        List<ProviderId> providerPriority) {
        return replay(transactions, splits, providerPriority).calculateThrough(asOf);
    }

    public static Replay replay(List<TransactionEntity> transactions,
                                Map<UUID, List<SplitEventEntity>> splits,
                                List<ProviderId> providerPriority) {
        return new Replay(transactions, splits, providerPriority);
    }

    private static List<SplitEventEntity> canonicalSplits(List<SplitEventEntity> source,
                                                          List<ProviderId> providerPriority) {
        if (source == null || source.isEmpty()) return List.of();
        Map<LocalDate, SplitEventEntity> selected = new java.util.LinkedHashMap<>();
        source.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(SplitEventEntity::getEffectiveDate)
                        .thenComparing(event -> ProviderPriority.rank(event.getSource(), providerPriority))
                        .thenComparing(SplitEventEntity::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                        .thenComparing(event -> event.getId() == null ? "" : event.getId().toString()))
                .forEach(event -> selected.putIfAbsent(event.getEffectiveDate(), event));
        return selected.values().stream().sorted(Comparator.comparing(SplitEventEntity::getEffectiveDate)).toList();
    }

    public static final class Replay {
        private final List<TransactionEntity> orderedTransactions;
        private final Map<UUID, List<SplitEventEntity>> canonicalSplits;
        private final Map<UUID, Deque<Lot>> lots = new HashMap<>();
        private final Map<UUID, BigDecimal> realizedByInstrument = new HashMap<>();
        private final Map<UUID, Integer> splitIndex = new HashMap<>();
        private int transactionIndex;
        private LocalDate currentAsOf;
        private BigDecimal realized = BigDecimal.ZERO;
        private BigDecimal dividends = BigDecimal.ZERO;
        private BigDecimal standaloneFees = BigDecimal.ZERO;
        private BigDecimal totalFees = BigDecimal.ZERO;

        private Replay(List<TransactionEntity> transactions, Map<UUID, List<SplitEventEntity>> splits,
                       List<ProviderId> providerPriority) {
            List<ProviderId> priority = providerPriority == null ? List.of() : providerPriority.stream()
                    .filter(java.util.Objects::nonNull).toList();
            orderedTransactions = (transactions == null ? List.<TransactionEntity>of() : transactions).stream()
                    .sorted(Comparator.comparing(TransactionEntity::getTradeDate)
                            .thenComparing(TransactionEntity::getLedgerOrder, Comparator.nullsLast(Long::compareTo))
                            .thenComparing(TransactionEntity::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                            .thenComparing(transaction -> transaction.getId() == null ? "" : transaction.getId().toString()))
                    .toList();
            canonicalSplits = splits == null ? Map.of() : splits.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                            entry -> canonicalSplits(entry.getValue(), priority), (first, ignored) -> first));
        }

        public Calculation calculateThrough(LocalDate asOf) {
            if (asOf == null) throw new IllegalArgumentException("Valuation date is required");
            if (currentAsOf != null && asOf.isBefore(currentAsOf)) {
                throw new IllegalArgumentException("Replay dates must be non-decreasing");
            }
            while (transactionIndex < orderedTransactions.size()
                    && !orderedTransactions.get(transactionIndex).getTradeDate().isAfter(asOf)) {
                TransactionEntity transaction = orderedTransactions.get(transactionIndex++);
                if (transaction.getTransactionType() == TransactionType.BUY
                        || transaction.getTransactionType() == TransactionType.SELL) {
                    if (transaction.getInstrument() == null) {
                        throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                                transaction.getTransactionType() + " requires an instrument");
                    }
                    UUID instrumentId = transaction.getInstrument().getId();
                    Deque<Lot> instrumentLots = lots.computeIfAbsent(instrumentId, ignored -> new ArrayDeque<>());
                    applySplitsThrough(instrumentId, instrumentLots, transaction.getTradeDate());
                    applyTransaction(transaction, instrumentId, instrumentLots);
                } else {
                    applyTransaction(transaction, null, null);
                }
            }
            // Apply corporate actions that occurred after the last security transaction but before valuation.
            lots.forEach((instrumentId, instrumentLots) -> applySplitsThrough(instrumentId, instrumentLots, asOf));
            currentAsOf = asOf;
            return snapshot();
        }

        private void applySplitsThrough(UUID instrumentId, Deque<Lot> instrumentLots, LocalDate date) {
            List<SplitEventEntity> instrumentSplits = canonicalSplits.getOrDefault(instrumentId, List.of());
            int index = splitIndex.getOrDefault(instrumentId, 0);
            while (index < instrumentSplits.size()
                    && !instrumentSplits.get(index).getEffectiveDate().isAfter(date)) {
                applySplit(instrumentLots, instrumentSplits.get(index));
                index++;
            }
            splitIndex.put(instrumentId, index);
        }

        private void applyTransaction(TransactionEntity transaction, UUID instrumentId, Deque<Lot> instrumentLots) {
            switch (transaction.getTransactionType()) {
                case BUY -> {
                    BigDecimal quantity = requiredPositive(transaction.getQuantity(), "BUY quantity");
                    BigDecimal price = required(transaction.getUnitPrice(), "BUY unit price");
                    BigDecimal fee = DecimalMath.zeroIfNull(transaction.getFee());
                    BigDecimal cost = quantity.multiply(price, MC).add(fee, MC);
                    instrumentLots.addLast(new Lot(quantity, cost.divide(quantity, MC), cost));
                    totalFees = totalFees.add(fee, MC);
                }
                case SELL -> {
                    BigDecimal quantity = requiredPositive(transaction.getQuantity(), "SELL quantity");
                    BigDecimal price = required(transaction.getUnitPrice(), "SELL unit price");
                    BigDecimal fee = DecimalMath.zeroIfNull(transaction.getFee());
                    BigDecimal cost = consume(instrumentLots, quantity);
                    BigDecimal proceeds = quantity.multiply(price, MC).subtract(fee, MC);
                    BigDecimal transactionRealized = proceeds.subtract(cost, MC);
                    realized = realized.add(transactionRealized, MC);
                    realizedByInstrument.merge(instrumentId, transactionRealized, (a, b) -> a.add(b, MC));
                    totalFees = totalFees.add(fee, MC);
                }
                case DIVIDEND -> {
                    rejectNonTradeFee(transaction);
                    dividends = dividends.add(required(transaction.getAmount(), "DIVIDEND amount"), MC);
                }
                case FEE -> {
                    rejectNonTradeFee(transaction);
                    BigDecimal fee = required(transaction.getAmount(), "FEE amount");
                    standaloneFees = standaloneFees.add(fee, MC);
                    totalFees = totalFees.add(fee, MC);
                }
                case DEPOSIT, WITHDRAWAL, INTEREST -> {
                    // Account cash events do not change FIFO lots or realized security P/L.
                }
            }
        }

        private Calculation snapshot() {
            Map<UUID, Deque<Lot>> copiedLots = new HashMap<>();
            lots.forEach((instrumentId, instrumentLots) -> {
                Deque<Lot> copy = new ArrayDeque<>();
                instrumentLots.forEach(lot -> copy.addLast(new Lot(lot.quantity(), lot.unitCost(), lot.costBasis())));
                copiedLots.put(instrumentId, copy);
            });
            return new Calculation(copiedLots, new HashMap<>(realizedByInstrument), realized, dividends,
                    standaloneFees, totalFees);
        }
    }

    private static void applySplit(Deque<Lot> lots, SplitEventEntity split) {
        if (split.getNumerator() == null || split.getDenominator() == null
                || split.getNumerator().signum() <= 0 || split.getDenominator().signum() <= 0) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SPLIT", "Split ratio must be positive");
        }
        BigDecimal ratio = split.getNumerator().divide(split.getDenominator(), MC);
        lots.forEach(lot -> {
            lot.quantity = lot.quantity.multiply(ratio, MC);
            lot.unitCost = lot.costBasis.divide(lot.quantity, MC);
        });
    }

    private static BigDecimal consume(Deque<Lot> lots, BigDecimal quantity) {
        BigDecimal remaining = quantity;
        BigDecimal cost = BigDecimal.ZERO;
        while (remaining.signum() > 0 && !lots.isEmpty()) {
            Lot lot = lots.peekFirst();
            BigDecimal used = lot.quantity.min(remaining);
            BigDecimal usedCost = used.compareTo(lot.quantity) == 0
                    ? lot.costBasis : lot.costBasis.multiply(used, MC).divide(lot.quantity, MC);
            cost = cost.add(usedCost, MC);
            lot.quantity = lot.quantity.subtract(used, MC);
            remaining = remaining.subtract(used, MC);
            lot.costBasis = lot.costBasis.subtract(usedCost, MC);
            if (lot.quantity.signum() == 0) {
                lot.costBasis = BigDecimal.ZERO;
                lots.removeFirst();
            } else {
                lot.unitCost = lot.costBasis.divide(lot.quantity, MC);
            }
        }
        if (remaining.signum() > 0) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_HOLDINGS",
                    "Sell quantity exceeds available FIFO holdings");
        }
        return cost;
    }

    private static BigDecimal requiredPositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", label + " is required and must be positive");
        }
        return value;
    }

    private static BigDecimal required(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", label + " is required and cannot be negative");
        }
        return value;
    }

    private static void rejectNonTradeFee(TransactionEntity transaction) {
        if (DecimalMath.zeroIfNull(transaction.getFee()).signum() > 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                    "DIVIDEND and FEE transactions must use amount for fees");
        }
    }

    public static final class Lot {
        private BigDecimal quantity;
        private BigDecimal unitCost;
        private BigDecimal costBasis;

        public Lot(BigDecimal quantity, BigDecimal unitCost) {
            this(quantity, unitCost, quantity.multiply(unitCost, MC));
        }

        private Lot(BigDecimal quantity, BigDecimal unitCost, BigDecimal costBasis) {
            this.quantity = quantity;
            this.unitCost = unitCost;
            this.costBasis = costBasis;
        }

        public BigDecimal quantity() { return quantity; }
        public BigDecimal unitCost() { return unitCost; }
        public BigDecimal costBasis() { return costBasis; }
    }

    public record Position(UUID instrumentId, BigDecimal shares, BigDecimal costBasis, BigDecimal realizedPnl) { }

    public static final class Calculation {
        private final Map<UUID, Deque<Lot>> lots;
        private final Map<UUID, BigDecimal> realizedByInstrument;
        private final BigDecimal realized;
        private final BigDecimal dividends;
        private final BigDecimal standaloneFees;
        private final BigDecimal totalFees;

        private Calculation(Map<UUID, Deque<Lot>> lots, Map<UUID, BigDecimal> realizedByInstrument,
                            BigDecimal realized, BigDecimal dividends, BigDecimal standaloneFees,
                            BigDecimal totalFees) {
            this.lots = lots;
            this.realizedByInstrument = realizedByInstrument;
            this.realized = realized;
            this.dividends = dividends;
            this.standaloneFees = standaloneFees;
            this.totalFees = totalFees;
        }

        public Map<UUID, Deque<Lot>> lots() { return lots; }
        public BigDecimal realized() { return realized; }
        public BigDecimal dividends() { return dividends; }
        public BigDecimal standaloneFees() { return standaloneFees; }
        public BigDecimal totalFees() { return totalFees; }

        public List<Position> positions() {
            List<Position> result = new ArrayList<>();
            lots.forEach((instrumentId, instrumentLots) -> {
                BigDecimal shares = instrumentLots.stream().map(Lot::quantity).reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
                BigDecimal basis = instrumentLots.stream().map(Lot::costBasis)
                        .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
                if (shares.signum() != 0) {
                    result.add(new Position(instrumentId, shares, basis,
                            realizedByInstrument.getOrDefault(instrumentId, BigDecimal.ZERO)));
                }
            });
            result.sort(Comparator.comparing(Position::instrumentId, Comparator.nullsFirst(Comparator.naturalOrder())));
            return result;
        }
    }
}
