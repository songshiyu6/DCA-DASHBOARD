package com.dca.terminal.portfolio;

import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.PriceDailyEntity;
import com.dca.terminal.marketdata.MarketDataEntities.QuoteLatestEntity;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.ProviderPriority;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.marketdata.PriceDailyRepository;
import com.dca.terminal.marketdata.QuoteLatestRepository;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.InvestmentPlanAssetEntity;
import com.dca.terminal.plan.AssetRepository;
import com.dca.terminal.plan.PlanRepository;
import com.dca.terminal.plan.PlanStatus;
import com.dca.terminal.transaction.FifoCalculator;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import com.dca.terminal.transaction.XirrCalculator;
import com.dca.terminal.observability.ObservabilityMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.portfolio.PortfolioDtos.HistoryPoint;
import static com.dca.terminal.portfolio.PortfolioDtos.HoldingResponse;
import static com.dca.terminal.portfolio.PortfolioDtos.SummaryResponse;

@Service
public class PortfolioService {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private final TransactionRepository transactionRepository;
    private final InstrumentRepository instrumentRepository;
    private final PriceDailyRepository priceRepository;
    private final QuoteLatestRepository quoteRepository;
    private final SplitEventRepository splitRepository;
    private final PortfolioSnapshotRepository snapshotRepository;
    private final PlanRepository planRepository;
    private final AssetRepository planAssetRepository;
    private final MarketDataService marketDataService;
    private final Clock clock;
    private final ZoneId zone;
    private final MeterRegistry meterRegistry;

    public PortfolioService(TransactionRepository transactionRepository,
                            InstrumentRepository instrumentRepository,
                            PriceDailyRepository priceRepository,
                            QuoteLatestRepository quoteRepository,
                            SplitEventRepository splitRepository,
                            PortfolioSnapshotRepository snapshotRepository,
                            PlanRepository planRepository,
                            AssetRepository planAssetRepository,
                            MarketDataService marketDataService,
                            Clock clock,
                            ZoneId zone) {
        this(transactionRepository, instrumentRepository, priceRepository, quoteRepository, splitRepository,
                snapshotRepository, planRepository, planAssetRepository, marketDataService, clock, zone,
                ObservabilityMetrics.noop());
    }

    @Autowired
    public PortfolioService(TransactionRepository transactionRepository,
                            InstrumentRepository instrumentRepository,
                            PriceDailyRepository priceRepository,
                            QuoteLatestRepository quoteRepository,
                            SplitEventRepository splitRepository,
                            PortfolioSnapshotRepository snapshotRepository,
                            PlanRepository planRepository,
                            AssetRepository planAssetRepository,
                            MarketDataService marketDataService,
                            Clock clock,
                            ZoneId zone,
                            MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.quoteRepository = quoteRepository;
        this.splitRepository = splitRepository;
        this.snapshotRepository = snapshotRepository;
        this.planRepository = planRepository;
        this.planAssetRepository = planAssetRepository;
        this.marketDataService = marketDataService;
        this.clock = clock;
        this.zone = zone;
        this.meterRegistry = meterRegistry == null ? ObservabilityMetrics.noop() : meterRegistry;
    }

    public record CurrentViews(SummaryResponse summary, List<HoldingResponse> holdings,
                               List<PortfolioDtos.AllocationResponse> allocation) { }

    @Transactional(readOnly = true)
    public CurrentViews currentViews() {
        LocalDate today = today();
        Ledger ledger = ledger(today);
        return new CurrentViews(summary(ledger, today), holdings(ledger), allocation(ledger));
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary() {
        LocalDate today = today();
        Ledger ledger = ledger(today);
        return summary(ledger, today);
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> holdings() {
        return holdings(ledger(today()));
    }

    @Transactional(readOnly = true)
    public List<HistoryPoint> history(String range) {
        LocalDate end = today();
        LocalDate requestedStart = rangeStart(end, range);
        List<TransactionEntity> allTransactions = transactionRepository.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
        if (allTransactions.isEmpty()) return List.of();
        LocalDate firstTransactionDate = allTransactions.stream().map(TransactionEntity::getTradeDate)
                .min(LocalDate::compareTo).orElse(requestedStart);
        LocalDate start = firstTransactionDate.isBefore(requestedStart) ? requestedStart : firstTransactionDate;

        Map<LocalDate, HistoryPoint> merged = new TreeMap<>();
        snapshotRepository.findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(requestedStart, end).stream()
                .filter(this::isUsableSnapshot)
                .filter(snapshot -> !snapshot.getSnapshotDate().isBefore(start)
                        && !snapshot.getSnapshotDate().isAfter(end))
                .sorted(Comparator.comparing(PortfolioSnapshotEntity::getSnapshotDate)
                        .thenComparing(snapshot -> snapshot.getId() == null ? "" : snapshot.getId().toString()))
                .forEach(snapshot -> merged.putIfAbsent(snapshot.getSnapshotDate(), historyPoint(snapshot)));

        boolean completeCoverage = true;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!merged.containsKey(date)) {
                completeCoverage = false;
                break;
            }
        }
        if (completeCoverage) return new ArrayList<>(merged.values());

        Timer.Sample sample = ObservabilityMetrics.start(meterRegistry);
        try {
            List<ProviderId> priority = marketDataService.providerPriority();
            Map<UUID, List<SplitEventEntity>> splits = splitMap(allTransactions, end, priority);
            Map<UUID, List<PriceDailyEntity>> prices = historicalPrices(allTransactions, end, priority);
            FifoCalculator.Replay replay = FifoCalculator.replay(allTransactions, splits, priority);
            Map<LocalDate, BigDecimal> dailyNetInvested = allTransactions.stream()
                    .collect(Collectors.toMap(TransactionEntity::getTradeDate, this::netInvestedChange,
                            (first, second) -> first.add(second, MC)));
            BigDecimal cumulativeNetInvested = netInvested(allTransactions, start.minusDays(1));
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                FifoCalculator.Calculation calculation = replay.calculateThrough(date);
                cumulativeNetInvested = cumulativeNetInvested.add(
                        dailyNetInvested.getOrDefault(date, BigDecimal.ZERO), MC);
                if (!merged.containsKey(date)) {
                    merged.put(date, replayHistoryPoint(date, calculation, cumulativeNetInvested, prices));
                }
            }
            return new ArrayList<>(merged.values());
        } finally {
            recordReplay("history", allTransactions.size(), sample);
        }
    }

    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> currentMarketValues() {
        Ledger ledger = ledger(today());
        return ledger.marketValues;
    }

    @Transactional(readOnly = true)
    public List<CurrentValuation> currentValuations(Collection<InstrumentEntity> instruments) {
        LocalDate today = today();
        Ledger ledger = ledger(today);
        Set<UUID> requestedIds = instruments.stream().filter(java.util.Objects::nonNull)
                .map(InstrumentEntity::getId).collect(Collectors.toSet());
        Map<UUID, PriceAtDate> currentPrices = new HashMap<>(ledger.prices);
        requestedIds.removeAll(currentPrices.keySet());
        currentPrices.putAll(loadCurrentPrices(requestedIds, today, marketDataService.providerPriority()));
        return instruments.stream()
                .filter(java.util.Objects::nonNull)
                .map(instrument -> {
                    PriceAtDate price = currentPrices.getOrDefault(instrument.getId(),
                            new PriceAtDate(null, FreshnessStatus.UNAVAILABLE, null));
                    BigDecimal value = price.price() == null
                            ? null : ledger.marketValues.getOrDefault(instrument.getId(), BigDecimal.ZERO);
                    return new CurrentValuation(instrument.getId(), value, price.price(), price.status());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioDtos.AllocationResponse> allocation() {
        return allocation(ledger(today()));
    }

    @Transactional
    public void rebuildTodaySnapshot() {
        Timer.Sample sample = ObservabilityMetrics.start(meterRegistry);
        try {
            LocalDate date = today();
            Ledger ledger = ledger(date);
            SummaryResponse summary = summary(ledger, date);
            PortfolioSnapshotEntity entity = snapshotRepository.findBySnapshotDate(date).orElseGet(PortfolioSnapshotEntity::new);
            entity.setSnapshotDate(date);
            entity.setMarketValue(summary.marketValue());
            entity.setCostBasis(summary.costBasis());
            entity.setNetCashFlow(summary.netInvested());
            entity.setRealizedPnl(summary.realizedPnl());
            entity.setUnrealizedPnl(summary.unrealizedPnl());
            entity.setDividendIncome(summary.dividendIncome());
            entity.setTotalFees(summary.totalFees());
            entity.setDataStatus(summary.status());
            snapshotRepository.save(entity);
        } finally {
            ObservabilityMetrics.stop(meterRegistry, sample, ObservabilityMetrics.SNAPSHOT_REBUILD);
        }
    }

    private List<HoldingResponse> holdings(Ledger ledger) {
        BigDecimal total = ledger.marketValue;
        return ledger.calculation.positions().stream()
                .filter(position -> position.shares().signum() != 0)
                .sorted(Comparator.comparing(position -> ledger.instruments.get(position.instrumentId()).getSymbol()))
                .map(position -> holding(position, ledger, total)).toList();
    }

    private List<PortfolioDtos.AllocationResponse> allocation(Ledger ledger) {
        BigDecimal total = ledger.marketValue;
        Map<UUID, InvestmentPlanAssetEntity> planned = new LinkedHashMap<>();
        planRepository.findFirstByStatus(PlanStatus.ACTIVE)
                .ifPresent(plan -> planAssetRepository.findAllByPlanIdOrderByIdAsc(plan.getId())
                        .forEach(asset -> planned.put(asset.getInstrument().getId(), asset)));

        List<PortfolioDtos.AllocationResponse> result = new ArrayList<>();
        planned.values().stream()
                .sorted(Comparator.comparing(asset -> asset.getInstrument().getSymbol()))
                .forEach(asset -> {
                    BigDecimal value = ledger.marketValues.get(asset.getInstrument().getId());
                    BigDecimal actual = !ledger.complete || value == null || total.signum() == 0
                            ? null : value.divide(total, MC);
                    BigDecimal target = asset.getTargetWeight();
                    result.add(new PortfolioDtos.AllocationResponse(asset.getInstrument().getSymbol(), target, actual,
                            actual == null ? null : actual.subtract(target, MC), value));
                });
        ledger.calculation.positions().stream()
                .filter(position -> !planned.containsKey(position.instrumentId()))
                .sorted(Comparator.comparing(position -> ledger.instruments.get(position.instrumentId()).getSymbol()))
                .forEach(position -> {
                    BigDecimal value = ledger.marketValues.get(position.instrumentId());
                    BigDecimal actual = !ledger.complete || value == null || total.signum() == 0
                            ? null : value.divide(total, MC);
                    InstrumentEntity instrument = ledger.instruments.get(position.instrumentId());
                    result.add(new PortfolioDtos.AllocationResponse(instrument.getSymbol(), null, actual, null, value));
                });
        return result;
    }

    private Ledger ledger(LocalDate asOf) {
        Timer.Sample sample = ObservabilityMetrics.start(meterRegistry);
        List<TransactionEntity> transactions = transactionRepository.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(asOf);
        try {
            List<ProviderId> priority = marketDataService.providerPriority();
            Map<UUID, List<SplitEventEntity>> splits = splitMap(transactions, asOf, priority);
            FifoCalculator.Calculation calculation = FifoCalculator.calculate(transactions, splits, asOf, priority);
            Map<UUID, InstrumentEntity> instruments = transactions.stream()
                    .collect(Collectors.toMap(transaction -> transaction.getInstrument().getId(), TransactionEntity::getInstrument, (a, b) -> a, LinkedHashMap::new));
            Map<UUID, BigDecimal> values = new LinkedHashMap<>();
            Map<UUID, PriceAtDate> prices = new HashMap<>();
            Set<UUID> positionIds = calculation.positions().stream().map(FifoCalculator.Position::instrumentId).collect(Collectors.toSet());
            Map<UUID, PriceAtDate> loadedPrices = loadCurrentPrices(positionIds, asOf, priority);
            BigDecimal marketValue = BigDecimal.ZERO;
            FreshnessStatus status = FreshnessStatus.FRESH;
            boolean complete = true;
            for (FifoCalculator.Position position : calculation.positions()) {
                PriceAtDate price = loadedPrices.getOrDefault(position.instrumentId(),
                        new PriceAtDate(null, FreshnessStatus.UNAVAILABLE, null));
                prices.put(position.instrumentId(), price);
                if (price.price() == null) {
                    status = FreshnessStatus.PARTIAL;
                    complete = false;
                    continue;
                }
                BigDecimal value = position.shares().multiply(price.price(), MC);
                values.put(position.instrumentId(), value);
                marketValue = marketValue.add(value, MC);
                if (price.status() != FreshnessStatus.FRESH) status = FreshnessStatus.PARTIAL;
            }
            return new Ledger(transactions, calculation, instruments, values, prices, marketValue, status, complete);
        } finally {
            recordReplay("current", transactions.size(), sample);
        }
    }

    private void recordReplay(String mode, int transactionCount, Timer.Sample sample) {
        ObservabilityMetrics.record(meterRegistry, ObservabilityMetrics.PORTFOLIO_REPLAY_TRANSACTIONS,
                transactionCount, "mode", mode);
        ObservabilityMetrics.stop(meterRegistry, sample, ObservabilityMetrics.PORTFOLIO_REPLAY, "mode", mode);
    }

    private SummaryResponse summary(Ledger ledger, LocalDate asOf) {
        BigDecimal costBasis = ledger.calculation.positions().stream().map(FifoCalculator.Position::costBasis)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        BigDecimal unrealized = ledger.complete ? ledger.marketValue.subtract(costBasis, MC) : null;
        BigDecimal invested = netInvested(ledger.transactions, asOf);
        BigDecimal totalPnl = ledger.complete ? ledger.calculation.realized().add(unrealized, MC)
                .add(ledger.calculation.dividends(), MC).subtract(ledger.calculation.standaloneFees(), MC) : null;
        BigDecimal xirr = ledger.complete
                ? XirrCalculator.solve(cashFlows(ledger.transactions, asOf, ledger.marketValue)) : null;
        return new SummaryResponse(ledger.marketValue, costBasis, invested, unrealized, ledger.calculation.realized(),
                ledger.calculation.dividends(), ledger.calculation.totalFees(), totalPnl, xirr, ledger.status, clock.instant());
    }

    private HoldingResponse holding(FifoCalculator.Position position, Ledger ledger, BigDecimal total) {
        InstrumentEntity instrument = ledger.instruments.get(position.instrumentId());
        PriceAtDate price = ledger.prices.get(position.instrumentId());
        BigDecimal value = ledger.marketValues.get(position.instrumentId());
        BigDecimal avgCost = position.shares().signum() == 0 ? BigDecimal.ZERO
                : position.costBasis().divide(position.shares(), MC);
        BigDecimal unrealized = value == null ? null : value.subtract(position.costBasis(), MC);
        BigDecimal returnRate = value == null || position.costBasis().signum() == 0
                ? null : unrealized.divide(position.costBasis(), MC);
        BigDecimal allocation = !ledger.complete || value == null || total.signum() == 0
                ? null : value.divide(total, MC);
        BigDecimal todayPercent = price.changePercent();
        return new HoldingResponse(instrument.getSymbol(), instrument.getName(), price.price(), todayPercent,
                position.shares(), avgCost, position.costBasis(), value, unrealized, returnRate, allocation,
                price.status());
    }

    private Map<UUID, List<SplitEventEntity>> splitMap(List<TransactionEntity> transactions, LocalDate asOf,
                                                      List<ProviderId> priority) {
        Set<UUID> ids = transactions.stream().map(transaction -> transaction.getInstrument().getId()).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Map<LocalDate, SplitEventEntity>> selected = new HashMap<>();
        splitRepository.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(ids, asOf)
                .stream()
                .sorted(Comparator.comparing((SplitEventEntity event) -> event.getInstrument().getId())
                        .thenComparing(SplitEventEntity::getEffectiveDate)
                        .thenComparing(event -> ProviderPriority.rank(event.getSource(), priority))
                        .thenComparing(SplitEventEntity::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                        .thenComparing(event -> event.getId() == null ? "" : event.getId().toString()))
                .forEach(event -> selected.computeIfAbsent(event.getInstrument().getId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(event.getEffectiveDate(), event));
        return selected.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().values().stream().sorted(Comparator.comparing(SplitEventEntity::getEffectiveDate)).toList()));
    }

    private Map<UUID, PriceAtDate> loadCurrentPrices(Collection<UUID> instrumentIds, LocalDate asOf,
                                                     List<ProviderId> priority) {
        if (instrumentIds.isEmpty()) return Map.of();
        Map<UUID, QuoteLatestEntity> quotes = quoteRepository.findAllByInstrumentIdIn(instrumentIds).stream()
                .collect(Collectors.toMap(QuoteLatestEntity::getInstrumentId, quote -> quote, (first, ignored) -> first));
        Map<UUID, List<PriceDailyEntity>> daily = historicalPrices(instrumentIds, asOf, priority);
        Map<UUID, PriceAtDate> result = new HashMap<>();
        for (UUID instrumentId : instrumentIds) {
            QuoteLatestEntity quote = quotes.get(instrumentId);
            result.put(instrumentId, currentPrice(instrumentId, quote, daily.getOrDefault(instrumentId, List.of())));
        }
        return result;
    }

    private PriceAtDate currentPrice(UUID instrumentId, QuoteLatestEntity quote, List<PriceDailyEntity> daily) {
        if (quote != null && quote.getPrice() != null && quote.getPrice().signum() > 0) {
            FreshnessStatus status = quote.getStatus() == null ? FreshnessStatus.STALE : quote.getStatus();
            return new PriceAtDate(quote.getPrice(), status, quote.getChangePercent());
        }
        PriceDailyEntity last = daily.stream().max(Comparator.comparing(PriceDailyEntity::getTradeDate)).orElse(null);
        return last == null || last.getClose() == null || last.getClose().signum() <= 0
                ? new PriceAtDate(null, FreshnessStatus.UNAVAILABLE, null)
                : new PriceAtDate(last.getClose(), FreshnessStatus.STALE, null);
    }

    private PriceAtDate priceAt(UUID instrumentId, LocalDate date, List<PriceDailyEntity> daily) {
        int low = 0;
        int high = daily.size() - 1;
        int best = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (!daily.get(middle).getTradeDate().isAfter(date)) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        PriceDailyEntity price = best < 0 ? null : daily.get(best);
        return price == null || price.getClose() == null || price.getClose().signum() <= 0
                ? new PriceAtDate(null, FreshnessStatus.UNAVAILABLE, null)
                : new PriceAtDate(price.getClose(), price.getTradeDate().equals(date) ? FreshnessStatus.FRESH : FreshnessStatus.STALE, null);
    }

    private HistoryPoint replayHistoryPoint(LocalDate date, FifoCalculator.Calculation calculation,
                                            BigDecimal netInvested, Map<UUID, List<PriceDailyEntity>> prices) {
        BigDecimal marketValue = BigDecimal.ZERO;
        FreshnessStatus status = FreshnessStatus.FRESH;
        boolean complete = true;
        for (FifoCalculator.Position position : calculation.positions()) {
            PriceAtDate price = priceAt(position.instrumentId(), date, prices.getOrDefault(position.instrumentId(), List.of()));
            if (price.price() == null) {
                status = FreshnessStatus.PARTIAL;
                complete = false;
                continue;
            }
            marketValue = marketValue.add(position.shares().multiply(price.price(), MC), MC);
            if (price.status() != FreshnessStatus.FRESH) status = FreshnessStatus.PARTIAL;
        }
        BigDecimal costBasis = calculation.positions().stream().map(FifoCalculator.Position::costBasis)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        return new HistoryPoint(date, complete ? marketValue : null, netInvested, costBasis,
                complete ? marketValue.subtract(costBasis, MC) : null, status);
    }

    private boolean isUsableSnapshot(PortfolioSnapshotEntity snapshot) {
        return snapshot != null && snapshot.getSnapshotDate() != null
                && snapshot.getMarketValue() != null && snapshot.getNetCashFlow() != null
                && snapshot.getCostBasis() != null && snapshot.getDataStatus() != null;
    }

    private HistoryPoint historyPoint(PortfolioSnapshotEntity snapshot) {
        return new HistoryPoint(snapshot.getSnapshotDate(), snapshot.getMarketValue(), snapshot.getNetCashFlow(),
                snapshot.getCostBasis(), snapshot.getUnrealizedPnl(), snapshot.getDataStatus());
    }

    private Map<UUID, List<PriceDailyEntity>> historicalPrices(Collection<UUID> instrumentIds, LocalDate asOf,
                                                               List<ProviderId> priority) {
        if (instrumentIds.isEmpty()) return Map.of();
        Map<UUID, Map<LocalDate, PriceDailyEntity>> selected = new HashMap<>();
        priceRepository.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(instrumentIds, asOf)
                .stream()
                .sorted(Comparator.comparing((PriceDailyEntity row) -> row.getInstrument().getId())
                        .thenComparing(PriceDailyEntity::getTradeDate)
                        .thenComparing(row -> ProviderPriority.rank(row.getSource(), priority))
                        .thenComparing(PriceDailyEntity::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                        .thenComparing(row -> row.getId() == null ? "" : row.getId().toString()))
                .forEach(row -> selected.computeIfAbsent(row.getInstrument().getId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(row.getTradeDate(), row));
        return selected.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().values().stream().sorted(Comparator.comparing(PriceDailyEntity::getTradeDate)).toList()));
    }

    private Map<UUID, List<PriceDailyEntity>> historicalPrices(List<TransactionEntity> transactions, LocalDate asOf,
                                                               List<ProviderId> priority) {
        return historicalPrices(transactions.stream().map(transaction -> transaction.getInstrument().getId()).collect(Collectors.toSet()),
                asOf, priority);
    }

    private BigDecimal netInvested(List<TransactionEntity> transactions, LocalDate asOf) {
        BigDecimal result = BigDecimal.ZERO;
        for (TransactionEntity transaction : transactions) {
            if (transaction.getTradeDate().isAfter(asOf)) continue;
            result = result.add(netInvestedChange(transaction), MC);
        }
        return result;
    }

    private BigDecimal netInvestedChange(TransactionEntity transaction) {
        BigDecimal fee = DecimalMath.zeroIfNull(transaction.getFee());
        return switch (transaction.getTransactionType()) {
            case BUY -> transaction.getQuantity().multiply(transaction.getUnitPrice(), MC).add(fee, MC);
            case SELL -> transaction.getQuantity().multiply(transaction.getUnitPrice(), MC).negate().add(fee, MC);
            case DIVIDEND, FEE -> BigDecimal.ZERO;
        };
    }

    private List<XirrCalculator.CashFlow> cashFlows(List<TransactionEntity> transactions, LocalDate asOf, BigDecimal currentValue) {
        List<XirrCalculator.CashFlow> flows = new ArrayList<>();
        for (TransactionEntity transaction : transactions) {
            if (transaction.getTradeDate().isAfter(asOf)) continue;
            BigDecimal fee = DecimalMath.zeroIfNull(transaction.getFee());
            BigDecimal amount = switch (transaction.getTransactionType()) {
                case BUY -> transaction.getQuantity().multiply(transaction.getUnitPrice(), MC).add(fee, MC).negate();
                case SELL -> transaction.getQuantity().multiply(transaction.getUnitPrice(), MC).subtract(fee, MC);
                case DIVIDEND -> transaction.getAmount();
                case FEE -> transaction.getAmount().negate();
            };
            flows.add(new XirrCalculator.CashFlow(transaction.getTradeDate(), amount));
        }
        if (currentValue != null && currentValue.signum() > 0) {
            flows.add(new XirrCalculator.CashFlow(asOf, currentValue));
        }
        return flows;
    }

    private LocalDate rangeStart(LocalDate end, String range) {
        if (range == null) return end.minusYears(1);
        return switch (range.toUpperCase()) {
            case "1M" -> end.minusMonths(1);
            case "3M" -> end.minusMonths(3);
            case "YTD" -> LocalDate.of(end.getYear(), 1, 1);
            case "3Y" -> end.minusYears(3);
            case "5Y", "ALL" -> end.minusYears(5);
            default -> end.minusYears(1);
        };
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(zone)); }

    private record PriceAtDate(BigDecimal price, FreshnessStatus status, BigDecimal changePercent) { }
    public record CurrentValuation(UUID instrumentId, BigDecimal marketValue, BigDecimal price,
                                   FreshnessStatus status) { }
    private record Ledger(List<TransactionEntity> transactions, FifoCalculator.Calculation calculation,
                          Map<UUID, InstrumentEntity> instruments, Map<UUID, BigDecimal> marketValues,
                          Map<UUID, PriceAtDate> prices, BigDecimal marketValue, FreshnessStatus status,
                          boolean complete) { }
}
