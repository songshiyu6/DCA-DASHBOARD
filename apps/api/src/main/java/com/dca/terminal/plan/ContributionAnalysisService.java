package com.dca.terminal.plan;

import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.marketdata.ProviderPriority;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.transaction.ContributionType;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.plan.ContributionDtos.ContributionAnalysisResponse;
import static com.dca.terminal.plan.ContributionDtos.ContributionBatchResponse;
import static com.dca.terminal.plan.ContributionDtos.ContributionBucketResponse;
import static com.dca.terminal.plan.ContributionDtos.UnclassifiedBuy;

@Service
public class ContributionAnalysisService {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private final PlanRepository planRepository;
    private final CycleRepository cycleRepository;
    private final TransactionRepository transactionRepository;
    private final SplitEventRepository splitRepository;
    private final MarketDataService marketDataService;
    private final PortfolioService portfolioService;
    private final Clock clock;
    private final ZoneId zone;

    public ContributionAnalysisService(PlanRepository planRepository,
                                       CycleRepository cycleRepository,
                                       TransactionRepository transactionRepository,
                                       SplitEventRepository splitRepository,
                                       MarketDataService marketDataService,
                                       PortfolioService portfolioService,
                                       Clock clock,
                                       ZoneId zone) {
        this.planRepository = planRepository;
        this.cycleRepository = cycleRepository;
        this.transactionRepository = transactionRepository;
        this.splitRepository = splitRepository;
        this.marketDataService = marketDataService;
        this.portfolioService = portfolioService;
        this.clock = clock;
        this.zone = zone;
    }

    @Transactional
    public void setInitialCapital(UUID planId, BigDecimal amount) {
        InvestmentPlanEntity plan = getPlan(planId);
        if (amount != null && amount.signum() < 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_INITIAL_CAPITAL",
                    "Initial capital cannot be negative");
        }
        plan.setInitialCapital(DecimalMath.money(amount));
        planRepository.saveAndFlush(plan);
    }

    @Transactional
    public void classifyInitial(UUID planId, UUID transactionId) {
        getPlan(planId);
        TransactionEntity transaction = getTransaction(transactionId);
        if (transaction.getTransactionType() != TransactionType.BUY) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INITIAL_CONTRIBUTION_REQUIRES_BUY",
                    "Only BUY transactions can be classified as initial capital");
        }
        if (transaction.getPlanCycleId() != null) {
            throw new DomainException(HttpStatus.CONFLICT, "DCA_CONTRIBUTION_ALREADY_CLASSIFIED",
                    "A BUY linked to a DCA cycle cannot also be initial capital");
        }
        if (transaction.getContributionType() == ContributionType.INITIAL
                && transaction.getContributionPlanId() != null
                && !planId.equals(transaction.getContributionPlanId())) {
            throw new DomainException(HttpStatus.CONFLICT, "CONTRIBUTION_ALREADY_CLASSIFIED",
                    "The transaction is already initial capital for another plan");
        }
        transaction.setContributionType(ContributionType.INITIAL);
        transaction.setContributionPlanId(planId);
        transactionRepository.saveAndFlush(transaction);
    }

    @Transactional
    public void unclassifyInitial(UUID planId, UUID transactionId) {
        TransactionEntity transaction = getTransaction(transactionId);
        if (transaction.getContributionType() != ContributionType.INITIAL
                || !planId.equals(transaction.getContributionPlanId())) {
            throw new DomainException(HttpStatus.CONFLICT, "CONTRIBUTION_NOT_INITIAL",
                    "The transaction is not initial capital for this plan");
        }
        transaction.setContributionType(null);
        transaction.setContributionPlanId(null);
        transactionRepository.saveAndFlush(transaction);
    }

    @Transactional(readOnly = true)
    public ContributionAnalysisResponse analyze(UUID planId) {
        InvestmentPlanEntity plan = getPlan(planId);
        LocalDate asOf = LocalDate.now(clock.withZone(zone));
        Map<UUID, String> cyclePeriods = cycleRepository.findAllByPlanIdOrderByPeriodAsc(planId).stream()
                .collect(Collectors.toMap(InvestmentPlanCycleEntity::getId, InvestmentPlanCycleEntity::getPeriod));
        List<TransactionEntity> transactions = transactionRepository
                .findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(asOf);
        Map<UUID, List<SplitEventEntity>> splits = splitMap(transactions, asOf);
        Map<UUID, Integer> splitIndex = new HashMap<>();
        Map<UUID, Deque<TaggedLot>> lots = new HashMap<>();
        Map<UUID, InstrumentEntity> instruments = new LinkedHashMap<>();
        Map<BatchKey, BatchAccumulator> accumulators = new LinkedHashMap<>();
        List<UnclassifiedBuy> unclassified = new ArrayList<>();
        BigDecimal unclassifiedAmount = BigDecimal.ZERO;

        for (TransactionEntity transaction : transactions) {
            UUID instrumentId = transaction.getInstrument().getId();
            instruments.putIfAbsent(instrumentId, transaction.getInstrument());
            Deque<TaggedLot> instrumentLots = lots.computeIfAbsent(instrumentId, ignored -> new ArrayDeque<>());
            applySplitsThrough(instrumentId, instrumentLots, transaction.getTradeDate(), splits, splitIndex);
            switch (transaction.getTransactionType()) {
                case BUY -> {
                    BigDecimal cost = buyCost(transaction);
                    BatchKey key = batchKey(transaction, planId, cyclePeriods);
                    instrumentLots.addLast(new TaggedLot(transaction.getQuantity(), cost, transaction.getTradeDate(), key));
                    if (key != null) accumulator(accumulators, key).principal =
                            accumulator(accumulators, key).principal.add(cost, MC);
                    if (isUnclassifiedBuy(transaction)) {
                        unclassifiedAmount = unclassifiedAmount.add(cost, MC);
                        unclassified.add(new UnclassifiedBuy(transaction.getId(), transaction.getTradeDate(),
                                transaction.getInstrument().getSymbol(), DecimalMath.money(cost)));
                    }
                }
                case SELL -> applySell(transaction, instrumentLots, accumulators);
                case DIVIDEND, FEE -> {
                    // V1 deliberately excludes dividends and standalone fees from contribution-batch P/L.
                }
            }
        }

        lots.forEach((instrumentId, instrumentLots) ->
                applySplitsThrough(instrumentId, instrumentLots, asOf, splits, splitIndex));

        List<InstrumentEntity> targetOpenInstruments = lots.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(lot -> lot.key != null && lot.quantity.signum() > 0))
                .map(entry -> instruments.get(entry.getKey()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, PortfolioService.CurrentValuation> valuations = portfolioService.currentValuations(targetOpenInstruments)
                .stream().collect(Collectors.toMap(PortfolioService.CurrentValuation::instrumentId,
                        valuation -> valuation, (first, ignored) -> first));

        lots.forEach((instrumentId, instrumentLots) -> {
            PortfolioService.CurrentValuation valuation = valuations.get(instrumentId);
            for (TaggedLot lot : instrumentLots) {
                if (lot.key == null || lot.quantity.signum() <= 0) continue;
                BatchAccumulator batch = accumulator(accumulators, lot.key);
                batch.openCost = batch.openCost.add(lot.costBasis, MC);
                batch.weightedMarketDays = batch.weightedMarketDays.add(
                        lot.costBasis.multiply(BigDecimal.valueOf(marketDays(lot.buyDate, asOf)), MC), MC);
                if (!usablePrice(valuation)) {
                    batch.complete = false;
                    batch.dataStatus = FreshnessStatus.PARTIAL;
                    continue;
                }
                batch.openValue = batch.openValue.add(lot.quantity.multiply(valuation.price(), MC), MC);
                batch.dataStatus = combineStatus(batch.dataStatus, valuation.status());
            }
        });

        List<Map.Entry<BatchKey, BatchAccumulator>> ordered = accumulators.entrySet().stream()
                .filter(entry -> entry.getValue().principal.signum() > 0)
                .sorted((left, right) -> compareKeys(left.getKey(), right.getKey()))
                .toList();
        List<ContributionBatchResponse> batches = ordered.stream()
                .map(entry -> toBatchResponse(entry.getKey(), entry.getValue()))
                .toList();

        List<BatchAccumulator> initialMembers = ordered.stream()
                .filter(entry -> entry.getKey().type == ContributionType.INITIAL)
                .map(Map.Entry::getValue).toList();
        List<BatchAccumulator> dcaMembers = ordered.stream()
                .filter(entry -> entry.getKey().type == ContributionType.DCA)
                .map(Map.Entry::getValue).toList();
        ContributionBucketResponse initial = toBucket(plan.getInitialCapital(), initialMembers);
        ContributionBucketResponse dca = toBucket(null, dcaMembers);
        BigDecimal totalInvested = initial.principal().add(dca.principal(), MC);
        FreshnessStatus dataStatus = combineStatus(initial.dataStatus(), dca.dataStatus());
        return new ContributionAnalysisResponse(DecimalMath.money(totalInvested), initial, dca,
                DecimalMath.money(unclassifiedAmount), List.copyOf(unclassified), batches, dataStatus, asOf);
    }

    private InvestmentPlanEntity getPlan(UUID planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Investment plan not found"));
    }

    private TransactionEntity getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found"));
    }

    private BigDecimal buyCost(TransactionEntity transaction) {
        if (transaction.getQuantity() == null || transaction.getQuantity().signum() <= 0
                || transaction.getUnitPrice() == null || transaction.getUnitPrice().signum() < 0) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSACTION",
                    "BUY transaction is missing quantity or price");
        }
        return transaction.getQuantity().multiply(transaction.getUnitPrice(), MC)
                .add(DecimalMath.zeroIfNull(transaction.getFee()), MC);
    }

    private BatchKey batchKey(TransactionEntity transaction, UUID planId, Map<UUID, String> cyclePeriods) {
        if (transaction.getContributionType() == ContributionType.INITIAL
                && planId.equals(transaction.getContributionPlanId())) {
            return new BatchKey(ContributionType.INITIAL, null);
        }
        String period = transaction.getPlanCycleId() == null ? null : cyclePeriods.get(transaction.getPlanCycleId());
        return period == null ? null : new BatchKey(ContributionType.DCA, period);
    }

    private boolean isUnclassifiedBuy(TransactionEntity transaction) {
        if (transaction.getTransactionType() != TransactionType.BUY || transaction.getPlanCycleId() != null) return false;
        return transaction.getContributionType() != ContributionType.INITIAL
                && transaction.getContributionType() != ContributionType.UNPLANNED;
    }

    private void applySell(TransactionEntity transaction, Deque<TaggedLot> lots,
                           Map<BatchKey, BatchAccumulator> accumulators) {
        if (transaction.getQuantity() == null || transaction.getQuantity().signum() <= 0
                || transaction.getUnitPrice() == null || transaction.getUnitPrice().signum() < 0) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSACTION",
                    "SELL transaction is missing quantity or price");
        }
        BigDecimal sellQuantity = transaction.getQuantity();
        BigDecimal remaining = sellQuantity;
        BigDecimal feeRemaining = DecimalMath.zeroIfNull(transaction.getFee());
        while (remaining.signum() > 0 && !lots.isEmpty()) {
            TaggedLot lot = lots.peekFirst();
            BigDecimal used = lot.quantity.min(remaining);
            BigDecimal usedCost = used.compareTo(lot.quantity) == 0
                    ? lot.costBasis : lot.costBasis.multiply(used, MC).divide(lot.quantity, MC);
            BigDecimal feeShare = used.compareTo(remaining) == 0
                    ? feeRemaining
                    : DecimalMath.zeroIfNull(transaction.getFee()).multiply(used, MC).divide(sellQuantity, MC);
            BigDecimal proceeds = used.multiply(transaction.getUnitPrice(), MC).subtract(feeShare, MC);
            if (lot.key != null) {
                BatchAccumulator batch = accumulator(accumulators, lot.key);
                batch.realized = batch.realized.add(proceeds.subtract(usedCost, MC), MC);
                batch.weightedMarketDays = batch.weightedMarketDays.add(
                        usedCost.multiply(BigDecimal.valueOf(marketDays(lot.buyDate, transaction.getTradeDate())), MC), MC);
            }
            lot.quantity = lot.quantity.subtract(used, MC);
            lot.costBasis = lot.costBasis.subtract(usedCost, MC);
            remaining = remaining.subtract(used, MC);
            feeRemaining = feeRemaining.subtract(feeShare, MC);
            if (lot.quantity.signum() == 0) lots.removeFirst();
        }
        if (remaining.signum() > 0) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_HOLDINGS",
                    "Sell quantity exceeds available FIFO holdings");
        }
    }

    private Map<UUID, List<SplitEventEntity>> splitMap(List<TransactionEntity> transactions, LocalDate asOf) {
        Set<UUID> instrumentIds = transactions.stream().map(transaction -> transaction.getInstrument().getId())
                .collect(Collectors.toSet());
        if (instrumentIds.isEmpty()) return Map.of();
        List<ProviderId> priority = marketDataService.providerPriority();
        Map<UUID, Map<LocalDate, SplitEventEntity>> selected = new HashMap<>();
        splitRepository.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                        instrumentIds, asOf).stream()
                .sorted(Comparator.comparing((SplitEventEntity event) -> event.getInstrument().getId())
                        .thenComparing(SplitEventEntity::getEffectiveDate)
                        .thenComparing(event -> ProviderPriority.rank(event.getSource(), priority))
                        .thenComparing(SplitEventEntity::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                        .thenComparing(event -> event.getId() == null ? "" : event.getId().toString()))
                .forEach(event -> selected.computeIfAbsent(event.getInstrument().getId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(event.getEffectiveDate(), event));
        return selected.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().values().stream()
                        .sorted(Comparator.comparing(SplitEventEntity::getEffectiveDate)).toList()));
    }

    private void applySplitsThrough(UUID instrumentId, Deque<TaggedLot> lots, LocalDate date,
                                    Map<UUID, List<SplitEventEntity>> splits, Map<UUID, Integer> splitIndex) {
        List<SplitEventEntity> events = splits.getOrDefault(instrumentId, List.of());
        int index = splitIndex.getOrDefault(instrumentId, 0);
        while (index < events.size() && !events.get(index).getEffectiveDate().isAfter(date)) {
            SplitEventEntity split = events.get(index++);
            if (split.getNumerator() == null || split.getDenominator() == null
                    || split.getNumerator().signum() <= 0 || split.getDenominator().signum() <= 0) {
                throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SPLIT", "Split ratio must be positive");
            }
            BigDecimal ratio = split.getNumerator().divide(split.getDenominator(), MC);
            lots.forEach(lot -> lot.quantity = lot.quantity.multiply(ratio, MC));
        }
        splitIndex.put(instrumentId, index);
    }

    private ContributionBatchResponse toBatchResponse(BatchKey key, BatchAccumulator batch) {
        BigDecimal pnl = batch.complete ? batch.realized.add(batch.openValue.subtract(batch.openCost, MC), MC) : null;
        BigDecimal value = pnl == null ? null : batch.principal.add(pnl, MC);
        BigDecimal returnRate = pnl == null || batch.principal.signum() == 0 ? null : pnl.divide(batch.principal, MC);
        return new ContributionBatchResponse(key.type, key.period, DecimalMath.money(batch.principal),
                DecimalMath.money(value), DecimalMath.money(pnl), returnRate, averageMarketDays(batch), batch.dataStatus);
    }

    private ContributionBucketResponse toBucket(BigDecimal plannedPrincipal, List<BatchAccumulator> members) {
        BigDecimal principal = members.stream().map(member -> member.principal)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal weightedDays = members.stream().map(member -> member.weightedMarketDays)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        boolean complete = members.stream().allMatch(member -> member.complete);
        FreshnessStatus status = members.stream().map(member -> member.dataStatus)
                .reduce(FreshnessStatus.FRESH, this::combineStatus);
        BigDecimal pnl = complete ? members.stream()
                .map(member -> member.realized.add(member.openValue.subtract(member.openCost, MC), MC))
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC)) : null;
        BigDecimal value = pnl == null ? null : principal.add(pnl, MC);
        BigDecimal returnRate = pnl == null || principal.signum() == 0 ? null : pnl.divide(principal, MC);
        int marketDays = principal.signum() == 0 ? 0
                : weightedDays.divide(principal, MC).setScale(0, RoundingMode.HALF_UP).intValue();
        int batchCount = (int) members.stream().filter(member -> member.principal.signum() > 0).count();
        return new ContributionBucketResponse(DecimalMath.money(plannedPrincipal), DecimalMath.money(principal),
                DecimalMath.money(value), DecimalMath.money(pnl), returnRate, marketDays, batchCount, status);
    }

    private int averageMarketDays(BatchAccumulator batch) {
        if (batch.principal.signum() == 0) return 0;
        return batch.weightedMarketDays.divide(batch.principal, MC)
                .setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private long marketDays(LocalDate start, LocalDate end) {
        return Math.max(0, ChronoUnit.DAYS.between(start, end));
    }

    private boolean usablePrice(PortfolioService.CurrentValuation valuation) {
        return valuation != null && valuation.price() != null && valuation.price().signum() > 0
                && valuation.status() != FreshnessStatus.UNAVAILABLE
                && valuation.status() != FreshnessStatus.INSUFFICIENT_HISTORY;
    }

    private FreshnessStatus combineStatus(FreshnessStatus left, FreshnessStatus right) {
        if (left == null) left = FreshnessStatus.STALE;
        if (right == null) right = FreshnessStatus.STALE;
        if (left == FreshnessStatus.PARTIAL || right == FreshnessStatus.PARTIAL
                || left == FreshnessStatus.UNAVAILABLE || right == FreshnessStatus.UNAVAILABLE
                || left == FreshnessStatus.INSUFFICIENT_HISTORY || right == FreshnessStatus.INSUFFICIENT_HISTORY) {
            return FreshnessStatus.PARTIAL;
        }
        if (left == FreshnessStatus.STALE || right == FreshnessStatus.STALE) return FreshnessStatus.STALE;
        return FreshnessStatus.FRESH;
    }

    private int compareKeys(BatchKey left, BatchKey right) {
        if (left.type != right.type) return left.type == ContributionType.INITIAL ? -1 : 1;
        if (left.period == null && right.period == null) return 0;
        if (left.period == null) return -1;
        if (right.period == null) return 1;
        return right.period.compareTo(left.period);
    }

    private BatchAccumulator accumulator(Map<BatchKey, BatchAccumulator> accumulators, BatchKey key) {
        return accumulators.computeIfAbsent(key, ignored -> new BatchAccumulator());
    }

    private record BatchKey(ContributionType type, String period) { }

    private static final class TaggedLot {
        private BigDecimal quantity;
        private BigDecimal costBasis;
        private final LocalDate buyDate;
        private final BatchKey key;

        private TaggedLot(BigDecimal quantity, BigDecimal costBasis, LocalDate buyDate, BatchKey key) {
            this.quantity = quantity;
            this.costBasis = costBasis;
            this.buyDate = buyDate;
            this.key = key;
        }
    }

    private static final class BatchAccumulator {
        private BigDecimal principal = BigDecimal.ZERO;
        private BigDecimal realized = BigDecimal.ZERO;
        private BigDecimal openCost = BigDecimal.ZERO;
        private BigDecimal openValue = BigDecimal.ZERO;
        private BigDecimal weightedMarketDays = BigDecimal.ZERO;
        private FreshnessStatus dataStatus = FreshnessStatus.FRESH;
        private boolean complete = true;
    }
}
