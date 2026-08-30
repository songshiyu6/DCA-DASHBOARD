package com.dca.terminal.plan;

import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.plan.PlanDtos.ContributionProgress;
import static com.dca.terminal.plan.PlanDtos.ContributionMonth;
import static com.dca.terminal.plan.PlanDtos.CycleAssetResponse;
import static com.dca.terminal.plan.PlanDtos.CycleResponse;
import static com.dca.terminal.plan.PlanDtos.NextDcaResponse;
import static com.dca.terminal.plan.PlanDtos.PlanAssetRequest;
import static com.dca.terminal.plan.PlanDtos.PlanAssetResponse;
import static com.dca.terminal.plan.PlanDtos.PlanRequest;
import static com.dca.terminal.plan.PlanDtos.PlanResponse;
import static com.dca.terminal.plan.PlanDtos.RecommendationItem;
import static com.dca.terminal.plan.PlanDtos.RecommendationResponse;

@Service
public class PlanService {
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.0001");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private final PlanRepository planRepository;
    private final AssetRepository assetRepository;
    private final CycleRepository cycleRepository;
    private final CycleAssetRepository cycleAssetRepository;
    private final InstrumentRepository instrumentRepository;
    private final TransactionRepository transactionRepository;
    private final PortfolioService portfolioService;
    private final Clock clock;
    private final ZoneId zone;

    public PlanService(PlanRepository planRepository,
                       AssetRepository assetRepository,
                       CycleRepository cycleRepository,
                       CycleAssetRepository cycleAssetRepository,
                       InstrumentRepository instrumentRepository,
                       TransactionRepository transactionRepository,
                       PortfolioService portfolioService,
                       Clock clock,
                       ZoneId zone) {
        this.planRepository = planRepository;
        this.assetRepository = assetRepository;
        this.cycleRepository = cycleRepository;
        this.cycleAssetRepository = cycleAssetRepository;
        this.instrumentRepository = instrumentRepository;
        this.transactionRepository = transactionRepository;
        this.portfolioService = portfolioService;
        this.clock = clock;
        this.zone = zone;
    }

    @Transactional(readOnly = true)
    public List<PlanResponse> list() {
        return planRepository.findAllByOrderByCreatedAtAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public InvestmentPlanEntity getEntity(UUID id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Investment plan not found"));
    }

    @Transactional
    public PlanResponse create(PlanRequest request) {
        validatePlan(request);
        PlanStatus status = request.status() == null ? PlanStatus.ACTIVE : request.status();
        if (status == PlanStatus.ACTIVE && planRepository.findFirstByStatus(PlanStatus.ACTIVE).isPresent()) {
            throw new DomainException(HttpStatus.CONFLICT, "ACTIVE_PLAN_EXISTS", "Only one active plan is supported");
        }
        InvestmentPlanEntity plan = new InvestmentPlanEntity();
        apply(plan, request, status);
        InvestmentPlanEntity saved = planRepository.saveAndFlush(plan);
        replaceAssets(saved, request.assets());
        ensureCycles(saved);
        return toResponse(saved);
    }

    @Transactional
    public PlanResponse update(UUID id, PlanRequest request) {
        validatePlan(request);
        InvestmentPlanEntity plan = getEntity(id);
        PlanStatus status = request.status() == null ? plan.getStatus() : request.status();
        if (status == PlanStatus.ACTIVE) {
            Optional<InvestmentPlanEntity> active = planRepository.findFirstByStatus(PlanStatus.ACTIVE);
            if (active.isPresent() && !active.get().getId().equals(id)) {
                throw new DomainException(HttpStatus.CONFLICT, "ACTIVE_PLAN_EXISTS", "Only one active plan is supported");
            }
        }
        apply(plan, request, status);
        planRepository.save(plan);
        replaceAssets(plan, request.assets());
        refreshUpcomingCycles(plan);
        return toResponse(plan);
    }

    @Transactional
    public void archive(UUID id) {
        InvestmentPlanEntity plan = getEntity(id);
        plan.setStatus(PlanStatus.ARCHIVED);
        planRepository.save(plan);
    }

    @Transactional
    public List<CycleResponse> cycles(UUID planId) {
        InvestmentPlanEntity plan = getEntity(planId);
        ensureCycles(plan);
        return cycleRepository.findAllByPlanIdOrderByPeriodAsc(planId).stream().map(this::refreshAndResponse).toList();
    }

    @Transactional
    public CycleResponse cycle(UUID planId, String period) {
        InvestmentPlanEntity plan = getEntity(planId);
        ensureCycle(plan, period);
        InvestmentPlanCycleEntity cycle = cycleRepository.findByPlanIdAndPeriod(planId, period)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "CYCLE_NOT_FOUND", "Plan cycle not found"));
        return refreshAndResponse(cycle);
    }

    @Transactional
    public RecommendationResponse recommendation(UUID planId, BigDecimal requestedAmount) {
        InvestmentPlanEntity plan = getEntity(planId);
        BigDecimal amount = requestedAmount == null ? plan.getMonthlyBudget() : requestedAmount;
        if (amount == null || amount.signum() < 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Contribution cannot be negative");
        }
        amount = DecimalMath.cents(amount);
        List<InvestmentPlanAssetEntity> assets = assetRepository.findAllByPlanIdOrderByIdAsc(planId);
        if (assets.isEmpty()) {
            return new RecommendationResponse(amount, FreshnessStatus.PARTIAL, List.of(),
                    "Plan has no assets; recommendation is unavailable");
        }
        Map<UUID, BigDecimal> allCurrentValues = portfolioService.currentMarketValues();
        // The denominator is deliberately limited to this plan's assets. Holdings outside the plan
        // must remain visible in the portfolio, but cannot change this plan's contribution split.
        Map<UUID, BigDecimal> currentValues = assets.stream().collect(java.util.stream.Collectors.toMap(
                asset -> asset.getInstrument().getId(),
                asset -> allCurrentValues.getOrDefault(asset.getInstrument().getId(), BigDecimal.ZERO)));
        BigDecimal total = currentValues.values().stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        Map<UUID, PortfolioService.CurrentValuation> valuations = portfolioService.currentValuations(
                assets.stream().map(InvestmentPlanAssetEntity::getInstrument).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(PortfolioService.CurrentValuation::instrumentId,
                        valuation -> valuation, (first, ignored) -> first));
        boolean unavailablePrice = !assets.isEmpty() && (valuations.size() < assets.size()
                || assets.stream().anyMatch(asset -> {
                    PortfolioService.CurrentValuation valuation = valuations.get(asset.getInstrument().getId());
                    return valuation == null || valuation.price() == null
                            || valuation.status() == FreshnessStatus.UNAVAILABLE;
                }));
        boolean stalePrice = !unavailablePrice && valuations.values().stream()
                .anyMatch(valuation -> valuation.status() != null && valuation.status() != FreshnessStatus.FRESH);
        List<RecommendationDraft> drafts = new ArrayList<>();
        BigDecimal positiveGapTotal = BigDecimal.ZERO;
        BigDecimal afterContribution = total.add(amount, MC);
        for (InvestmentPlanAssetEntity asset : assets) {
            BigDecimal current = currentValues.getOrDefault(asset.getInstrument().getId(), BigDecimal.ZERO);
            BigDecimal currentWeight = total.signum() == 0 ? BigDecimal.ZERO : current.divide(total, MC);
            BigDecimal target = afterContribution.multiply(asset.getTargetWeight(), MC);
            BigDecimal gap = target.subtract(current, MC);
            BigDecimal positiveGap = gap.max(BigDecimal.ZERO);
            positiveGapTotal = positiveGapTotal.add(positiveGap, MC);
            drafts.add(new RecommendationDraft(asset.getInstrument(), currentWeight, asset.getTargetWeight(), current, gap, positiveGap,
                    unavailablePrice ? "PRICE_UNAVAILABLE" : stalePrice ? "PRICE_STALE" : reason(gap)));
        }
        if (!unavailablePrice) allocate(drafts, amount, positiveGapTotal);
        else drafts.forEach(draft -> draft.setSuggestedAmount(BigDecimal.ZERO.setScale(2)));
        List<RecommendationItem> items = drafts.stream()
                .sorted(Comparator.comparing(draft -> draft.instrument().getSymbol()))
                .map(draft -> new RecommendationItem(draft.instrument().getSymbol(), draft.currentWeight(), draft.targetWeight(),
                        draft.currentValue(), draft.targetWeight().subtract(draft.currentWeight(), MC),
                        draft.suggestedAmount(), draft.positiveGap(), draft.reason(), draft.gap())).toList();
        FreshnessStatus status = unavailablePrice ? FreshnessStatus.PARTIAL : stalePrice ? FreshnessStatus.STALE : FreshnessStatus.FRESH;
        String message = unavailablePrice ? "One or more plan assets have no usable price"
                : stalePrice ? "Market data is stale; recommendation uses last available prices" : null;
        return new RecommendationResponse(amount, status, items, message);
    }

    @Transactional(readOnly = true)
    public PlanResponse get(UUID id) {
        return toResponse(getEntity(id));
    }

    @Transactional
    public ContributionProgress contributionProgress(UUID planId) {
        List<CycleResponse> cycles = cycles(planId);
        int year = LocalDate.now(clock.withZone(zone)).getYear();
        List<CycleResponse> annualCycles = cycles.stream()
                .filter(cycle -> YearMonth.parse(cycle.period()).getYear() == year).toList();
        BigDecimal planned = annualCycles.stream()
                .map(CycleResponse::plannedAmount).reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        BigDecimal executed = annualCycles.stream().map(CycleResponse::executedAmount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        BigDecimal cappedExecuted = annualCycles.stream().filter(cycle -> cycle.status() != CycleStatus.UPCOMING)
                .map(cycle -> cycle.executedAmount().min(cycle.plannedAmount()))
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        BigDecimal startedPlanned = annualCycles.stream().filter(cycle -> cycle.status() != CycleStatus.UPCOMING)
                .map(CycleResponse::plannedAmount).reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        BigDecimal rate = startedPlanned.signum() == 0 ? BigDecimal.ZERO
                : cappedExecuted.divide(startedPlanned, MC).min(BigDecimal.ONE);
        List<ContributionMonth> months = annualCycles.stream()
                .map(cycle -> new ContributionMonth(cycle.period(), cycle.plannedAmount(), cycle.executedAmount(), cycle.status()))
                .toList();
        return new ContributionProgress(year, executed, planned, planned.subtract(executed, MC).max(BigDecimal.ZERO), rate, months);
    }

    @Transactional
    public Optional<NextDcaResponse> nextDca(UUID planId) {
        InvestmentPlanEntity plan = getEntity(planId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        YearMonth currentPeriod = YearMonth.from(today);
        List<CycleResponse> candidates = cycles(planId).stream()
                .filter(cycle -> cycle.status() == CycleStatus.OPEN
                        || cycle.status() == CycleStatus.UPCOMING
                        || (cycle.status() == CycleStatus.PARTIAL
                        && YearMonth.parse(cycle.period()).equals(currentPeriod)
                        && today.getDayOfMonth() <= executionEndDay(currentPeriod, plan)))
                .sorted(Comparator.comparing(CycleResponse::period))
                .toList();
        if (candidates.isEmpty()) return Optional.empty();
        CycleResponse cycle = candidates.get(0);
        BigDecimal remaining = cycle.plannedAmount().subtract(cycle.executedAmount(), MC).max(BigDecimal.ZERO);
        RecommendationResponse recommendation = recommendation(planId, remaining);
        return Optional.of(new NextDcaResponse(cycle.period(), remaining,
                daysUntilWindow(today, YearMonth.parse(cycle.period()), plan), recommendation.items(),
                recommendation.status(), recommendation.message()));
    }

    @Transactional(readOnly = true)
    public void validateCycleForTransaction(UUID cycleId, UUID instrumentId, TransactionType type, LocalDate tradeDate) {
        if (cycleId == null) return;
        InvestmentPlanCycleEntity cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PLAN_CYCLE", "Plan cycle not found"));
        if (type != TransactionType.BUY) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "PLAN_CYCLE_REQUIRES_BUY",
                    "Only BUY transactions can be linked to a plan cycle");
        }
        if (tradeDate == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "PLAN_CYCLE_DATE_REQUIRED",
                    "A plan-cycle transaction requires a trade date");
        }
        YearMonth period = YearMonth.parse(cycle.getPeriod());
        if (!YearMonth.from(tradeDate).equals(period)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "PLAN_CYCLE_PERIOD_MISMATCH",
                    "Trade date must be inside the selected plan cycle");
        }
        if (isInitialCapitalMonth(cycle.getPlan(), period)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INITIAL_CAPITAL_MONTH_SKIPS_DCA",
                    "A month containing initial capital does not run a DCA cycle");
        }
        int lastDay = period.lengthOfMonth();
        int startDay = Math.min(cycle.getPlan().getExecutionStartDay(), lastDay);
        int endDay = Math.min(cycle.getPlan().getExecutionEndDay(), lastDay);
        if (tradeDate.getDayOfMonth() < startDay || tradeDate.getDayOfMonth() > endDay) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "PLAN_CYCLE_OUTSIDE_WINDOW",
                    "Trade date is outside the plan cycle execution window");
        }
        if (cycleAssetRepository.findAllByCycleIdOrderByIdAsc(cycleId).stream()
                .noneMatch(asset -> asset.getInstrument().getId().equals(instrumentId))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PLAN_CYCLE", "Instrument is not part of this plan cycle");
        }
    }

    private void validatePlan(PlanRequest request) {
        if (request == null || request.assets() == null || request.assets().isEmpty()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PLAN", "Plan must contain at least one ETF");
        }
        if (request.name() == null || request.name().isBlank() || request.monthlyBudget() == null
                || request.monthlyBudget().signum() <= 0 || request.startDate() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PLAN", "Plan name, budget and start date are required");
        }
        if (request.frequency() != null && request.frequency() != PlanFrequency.MONTHLY) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FREQUENCY", "v1 UI supports monthly plans only");
        }
        BigDecimal sum = BigDecimal.ZERO;
        java.util.Set<String> symbols = new java.util.HashSet<>();
        for (PlanAssetRequest asset : request.assets()) {
            if (asset == null || asset.symbol() == null || asset.symbol().isBlank()
                    || asset.targetWeight() == null || asset.targetWeight().signum() <= 0
                    || asset.targetWeight().compareTo(BigDecimal.ONE) > 0) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_WEIGHT", "Target weights must be positive");
            }
            String symbol = asset.symbol().trim().toUpperCase(java.util.Locale.ROOT);
            if (!symbols.add(symbol)) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "DUPLICATE_PLAN_ASSET", "Plan contains duplicate ETF: " + symbol);
            }
            instrumentRepository.findBySymbolIgnoreCase(symbol)
                    .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND", "ETF not found: " + asset.symbol()));
            sum = sum.add(asset.targetWeight(), MC);
        }
        if (sum.subtract(BigDecimal.ONE, MC).abs().compareTo(WEIGHT_TOLERANCE) > 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_WEIGHT_SUM", "Target weights must sum to 100%");
        }
        int start = request.executionStartDay() == null ? 1 : request.executionStartDay();
        int end = request.executionEndDay() == null ? 7 : request.executionEndDay();
        if (start < 1 || start > 31 || end < start || end > 31) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_EXECUTION_WINDOW", "Execution window is invalid");
        }
    }

    private int daysUntilWindow(LocalDate today, YearMonth period, InvestmentPlanEntity plan) {
        int startDay = executionStartDay(period, plan);
        int endDay = executionEndDay(period, plan);
        LocalDate firstExecutionDate = period.atDay(startDay);
        if (period.equals(YearMonth.from(today))
                && today.getDayOfMonth() >= startDay
                && today.getDayOfMonth() <= endDay) {
            return Math.max(0, endDay - today.getDayOfMonth() + 1);
        }
        if (period.equals(YearMonth.from(today)) && today.isBefore(firstExecutionDate)) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, firstExecutionDate);
            return (int) Math.max(0, days);
        }
        if (period.isAfter(YearMonth.from(today))) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, firstExecutionDate);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, days));
        }
        return 0;
    }

    private int executionStartDay(YearMonth period, InvestmentPlanEntity plan) {
        return Math.min(plan.getExecutionStartDay(), period.lengthOfMonth());
    }

    private int executionEndDay(YearMonth period, InvestmentPlanEntity plan) {
        return Math.min(plan.getExecutionEndDay(), period.lengthOfMonth());
    }

    private boolean isInitialCapitalMonth(InvestmentPlanEntity plan, YearMonth period) {
        if (plan == null || plan.getId() == null || period == null) return false;
        return transactionRepository.existsByContributionTypeAndContributionPlanIdAndTradeDateBetween(
                ContributionType.INITIAL, plan.getId(), period.atDay(1), period.atEndOfMonth());
    }

    private void apply(InvestmentPlanEntity plan, PlanRequest request, PlanStatus status) {
        plan.setName(request.name().trim());
        plan.setCurrency("USD");
        plan.setFrequency(request.frequency() == null ? PlanFrequency.MONTHLY : request.frequency());
        plan.setMonthlyBudget(DecimalMath.money(request.monthlyBudget()));
        plan.setStartDate(request.startDate());
        plan.setExecutionStartDay(request.executionStartDay() == null ? 1 : request.executionStartDay());
        plan.setExecutionEndDay(request.executionEndDay() == null ? 7 : request.executionEndDay());
        plan.setStatus(status);
    }

    private void replaceAssets(InvestmentPlanEntity plan, List<PlanAssetRequest> requests) {
        assetRepository.deleteAllByPlanId(plan.getId());
        assetRepository.flush();
        for (PlanAssetRequest request : requests) {
            InvestmentPlanAssetEntity asset = new InvestmentPlanAssetEntity();
            asset.setPlan(plan);
            asset.setInstrument(instrumentRepository.findBySymbolIgnoreCase(request.symbol().trim()).orElseThrow());
            asset.setTargetWeight(request.targetWeight());
            assetRepository.save(asset);
        }
    }

    private void ensureCycles(InvestmentPlanEntity plan) {
        YearMonth start = YearMonth.from(plan.getStartDate());
        YearMonth end = YearMonth.from(LocalDate.now(clock.withZone(zone))).withMonth(12);
        for (YearMonth period = start; !period.isAfter(end); period = period.plusMonths(1)) ensureCycle(plan, period.toString());
    }

    private void ensureCycle(InvestmentPlanEntity plan, String period) {
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(period);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_CYCLE_PERIOD", "Cycle period must be YYYY-MM");
        }
        if (YearMonth.from(plan.getStartDate()).isAfter(parsed)) return;
        InvestmentPlanCycleEntity cycle = cycleRepository.findByPlanIdAndPeriod(plan.getId(), period).orElseGet(() -> {
            InvestmentPlanCycleEntity newCycle = new InvestmentPlanCycleEntity();
            newCycle.setPlan(plan);
            newCycle.setPeriod(period);
            newCycle.setPlannedAmount(plan.getMonthlyBudget());
            newCycle.setExecutedAmount(BigDecimal.ZERO);
            newCycle.setStatus(CycleStatus.UPCOMING);
            InvestmentPlanCycleEntity saved = cycleRepository.saveAndFlush(newCycle);
            createCycleAssets(saved, assetRepository.findAllByPlanIdOrderByIdAsc(plan.getId()));
            return saved;
        });
        if (cycle.getPlannedAmount() == null) {
            cycle.setPlannedAmount(plan.getMonthlyBudget());
            cycleRepository.save(cycle);
        }
    }

    private void createCycleAssets(InvestmentPlanCycleEntity cycle, List<InvestmentPlanAssetEntity> assets) {
        for (InvestmentPlanAssetEntity source : assets) {
            InvestmentPlanCycleAssetEntity target = new InvestmentPlanCycleAssetEntity();
            target.setId(UUID.randomUUID());
            target.setCycle(cycle);
            target.setInstrument(source.getInstrument());
            target.setTargetWeight(source.getTargetWeight());
            target.setPlannedAmount(cycle.getPlannedAmount().multiply(source.getTargetWeight(), MC));
            target.setExecutedAmount(BigDecimal.ZERO);
            cycleAssetRepository.save(target);
        }
    }

    private void refreshUpcomingCycles(InvestmentPlanEntity plan) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        for (InvestmentPlanCycleEntity cycle : cycleRepository.findAllByPlanIdOrderByPeriodAsc(plan.getId())) {
            List<TransactionEntity> linkedTransactions = transactionRepository
                    .findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycle.getId());
            if (isMutableFutureCycle(cycle, today, !linkedTransactions.isEmpty())) {
                cycleAssetRepository.deleteAllByCycleId(cycle.getId());
                cycleAssetRepository.flush();
                cycle.setPlannedAmount(plan.getMonthlyBudget());
                createCycleAssets(cycle, assetRepository.findAllByPlanIdOrderByIdAsc(plan.getId()));
                cycleRepository.save(cycle);
            }
        }
    }

    static boolean isMutableFutureCycle(InvestmentPlanCycleEntity cycle, LocalDate today,
                                        boolean hasLinkedTransactions) {
        if (cycle == null || cycle.getPlan() == null || cycle.getPeriod() == null
                || today == null || hasLinkedTransactions) return false;
        YearMonth period = YearMonth.parse(cycle.getPeriod());
        int startDay = Math.max(1, Math.min(cycle.getPlan().getExecutionStartDay(), period.lengthOfMonth()));
        return period.atDay(startDay).isAfter(today);
    }

    private CycleResponse refreshAndResponse(InvestmentPlanCycleEntity cycle) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<TransactionEntity> transactions = transactionRepository
                .findAllByPlanCycleIdOrderByTradeDateAscLedgerOrderAscIdAsc(cycle.getId()).stream()
                .filter(transaction -> transaction.getTradeDate() != null
                        && !transaction.getTradeDate().isAfter(today))
                .toList();
        BigDecimal executed = transactions.stream().filter(transaction -> transaction.getTransactionType() == TransactionType.BUY)
                .map(transaction -> transaction.getQuantity().multiply(transaction.getUnitPrice(), MC).add(DecimalMath.zeroIfNull(transaction.getFee()), MC))
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        YearMonth period = YearMonth.parse(cycle.getPeriod());
        boolean skipForInitialCapital = executed.signum() == 0 && isInitialCapitalMonth(cycle.getPlan(), period);
        BigDecimal effectivePlannedAmount = skipForInitialCapital ? BigDecimal.ZERO : cycle.getPlannedAmount();
        CycleStatus status;
        LocalDate windowStart = period.atDay(executionStartDay(period, cycle.getPlan()));
        LocalDate windowEnd = period.atDay(executionEndDay(period, cycle.getPlan()));
        if (skipForInitialCapital) status = CycleStatus.SKIPPED;
        else if (today.isBefore(windowStart)) status = CycleStatus.UPCOMING;
        else if (executed.compareTo(cycle.getPlannedAmount()) >= 0) status = CycleStatus.COMPLETED;
        else if (executed.signum() > 0) status = CycleStatus.PARTIAL;
        else if (!today.isAfter(windowEnd)) status = CycleStatus.OPEN;
        else status = CycleStatus.SKIPPED;
        cycle.setExecutedAmount(executed);
        cycle.setStatus(status);
        if (executed.signum() > 0 && cycle.getOpenedAt() == null) cycle.setOpenedAt(clock.instant());
        if (status == CycleStatus.COMPLETED && cycle.getCompletedAt() == null) cycle.setCompletedAt(clock.instant());
        cycleRepository.save(cycle);
        Map<UUID, BigDecimal> byInstrument = new HashMap<>();
        transactions.stream().filter(transaction -> transaction.getTransactionType() == TransactionType.BUY)
                .forEach(transaction -> byInstrument.merge(transaction.getInstrument().getId(),
                        transaction.getQuantity().multiply(transaction.getUnitPrice(), MC).add(DecimalMath.zeroIfNull(transaction.getFee()), MC),
                        (a, b) -> a.add(b, MC)));
        List<CycleAssetResponse> assetResponses = cycleAssetRepository.findAllByCycleIdOrderByIdAsc(cycle.getId()).stream()
                .map(asset -> {
                    BigDecimal assetExecuted = byInstrument.getOrDefault(asset.getInstrument().getId(), BigDecimal.ZERO);
                    asset.setExecutedAmount(assetExecuted);
                    cycleAssetRepository.save(asset);
                    BigDecimal assetPlanned = skipForInitialCapital ? BigDecimal.ZERO : asset.getPlannedAmount();
                    return new CycleAssetResponse(asset.getInstrument().getSymbol(), asset.getTargetWeight(), assetPlanned, assetExecuted);
                }).toList();
        return new CycleResponse(cycle.getId(), cycle.getPlan().getId(), cycle.getPeriod(), effectivePlannedAmount,
                executed, status, assetResponses, cycle.getOpenedAt(), cycle.getCompletedAt());
    }

    private PlanResponse toResponse(InvestmentPlanEntity plan) {
        List<PlanAssetResponse> assets = assetRepository.findAllByPlanIdOrderByIdAsc(plan.getId()).stream()
                .map(asset -> new PlanAssetResponse(asset.getInstrument().getSymbol(), asset.getInstrument().getName(),
                        asset.getTargetWeight(), plan.getMonthlyBudget().multiply(asset.getTargetWeight(), MC))).toList();
        return new PlanResponse(plan.getId(), plan.getName(), plan.getCurrency(), plan.getFrequency(), plan.getMonthlyBudget(),
                plan.getStartDate(), plan.getExecutionStartDay(), plan.getExecutionEndDay(), plan.getStatus(), assets,
                plan.getCreatedAt(), plan.getUpdatedAt());
    }

    private String reason(BigDecimal gap) {
        if (gap == null) return "PRICE_UNAVAILABLE";
        return gap.signum() < 0 ? "OVERWEIGHT" : gap.signum() > 0 ? "UNDERWEIGHT" : "AT_TARGET";
    }

    private void allocate(List<RecommendationDraft> drafts, BigDecimal amount, BigDecimal gapTotal) {
        boolean useTargetWeights = gapTotal.signum() == 0;
        if (useTargetWeights) {
            gapTotal = drafts.stream().map(RecommendationDraft::targetWeight)
                    .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (RecommendationDraft draft : drafts) {
            BigDecimal basis = useTargetWeights ? draft.targetWeight() : draft.positiveGap();
            BigDecimal raw = amount.multiply(basis, MC).divide(gapTotal, MC);
            BigDecimal floor = raw.setScale(2, RoundingMode.DOWN);
            draft.setSuggestedAmount(floor);
            draft.setRemainder(raw.subtract(floor, MC));
            allocated = allocated.add(floor, MC);
        }
        BigDecimal pennies = amount.subtract(allocated, MC).setScale(2, RoundingMode.DOWN);
        drafts.sort(Comparator.comparing(RecommendationDraft::remainder, Comparator.reverseOrder())
                .thenComparing(draft -> draft.instrument().getSymbol()));
        while (pennies.compareTo(new BigDecimal("0.01")) >= 0) {
            for (RecommendationDraft draft : drafts) {
                if (pennies.compareTo(new BigDecimal("0.01")) < 0) break;
                if (useTargetWeights || draft.positiveGap().signum() > 0) {
                    draft.setSuggestedAmount(draft.suggestedAmount().add(new BigDecimal("0.01")));
                    pennies = pennies.subtract(new BigDecimal("0.01"));
                }
            }
        }
    }

    private static final class RecommendationDraft {
        private final InstrumentEntity instrument;
        private final BigDecimal currentWeight;
        private final BigDecimal targetWeight;
        private final BigDecimal currentValue;
        private final BigDecimal gap;
        private final BigDecimal positiveGap;
        private final String reason;
        private BigDecimal suggestedAmount = BigDecimal.ZERO.setScale(2);
        private BigDecimal remainder = BigDecimal.ZERO;

        private RecommendationDraft(InstrumentEntity instrument, BigDecimal currentWeight, BigDecimal targetWeight,
                                    BigDecimal currentValue, BigDecimal gap, BigDecimal positiveGap, String reason) {
            this.instrument = instrument;
            this.currentWeight = currentWeight;
            this.targetWeight = targetWeight;
            this.currentValue = currentValue;
            this.gap = gap;
            this.positiveGap = positiveGap;
            this.reason = reason;
        }
        InstrumentEntity instrument() { return instrument; }
        BigDecimal currentWeight() { return currentWeight; }
        BigDecimal targetWeight() { return targetWeight; }
        BigDecimal currentValue() { return currentValue; }
        BigDecimal gap() { return gap; }
        BigDecimal positiveGap() { return positiveGap; }
        String reason() { return reason; }
        BigDecimal suggestedAmount() { return suggestedAmount; }
        void setSuggestedAmount(BigDecimal value) { suggestedAmount = value; }
        BigDecimal remainder() { return remainder; }
        void setRemainder(BigDecimal value) { remainder = value; }
    }
}
