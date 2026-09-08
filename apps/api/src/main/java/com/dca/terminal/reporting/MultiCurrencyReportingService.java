package com.dca.terminal.reporting;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.fund.AutoDcaDtos;
import com.dca.terminal.fund.AutoDcaProjectionEngine;
import com.dca.terminal.fund.AutoDcaService;
import com.dca.terminal.fund.FundMarketCalendarDayRepository;
import com.dca.terminal.fund.FundProfileRepository;
import com.dca.terminal.fx.FxDtos;
import com.dca.terminal.fx.FxRateEntity;
import com.dca.terminal.fx.FxService;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import com.dca.terminal.performance.CashLedgerPortfolioPerformanceSource;
import com.dca.terminal.performance.PerformanceEngine;
import com.dca.terminal.performance.PortfolioPerformanceSource;
import com.dca.terminal.portfolio.PortfolioDtos;
import com.dca.terminal.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MultiCurrencyReportingService {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final String FLOW_MODEL = "USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY";
    private static final long MAX_FX_CARRY_DAYS = 7;

    private final PortfolioService portfolioService;
    private final CashLedgerPortfolioPerformanceSource usdPerformanceSource;
    private final AutoDcaService autoDcaService;
    private final FundNavDailyRepository navRepository;
    private final FundProfileRepository profileRepository;
    private final FundMarketCalendarDayRepository calendarRepository;
    private final FxService fxService;
    private final Clock clock;

    public MultiCurrencyReportingService(
            PortfolioService portfolioService,
            CashLedgerPortfolioPerformanceSource usdPerformanceSource,
            AutoDcaService autoDcaService,
            FundNavDailyRepository navRepository,
            FundProfileRepository profileRepository,
            FundMarketCalendarDayRepository calendarRepository,
            FxService fxService,
            Clock clock) {
        this.portfolioService = portfolioService;
        this.usdPerformanceSource = usdPerformanceSource;
        this.autoDcaService = autoDcaService;
        this.navRepository = navRepository;
        this.profileRepository = profileRepository;
        this.calendarRepository = calendarRepository;
        this.fxService = fxService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MultiCurrencyDtos.Response report(String range) {
        LocalDate today = LocalDate.now(clock);
        PortfolioDtos.SummaryResponse usdSummary = portfolioService.summary();
        List<PortfolioDtos.HistoryPoint> usdHistory = portfolioService.history("ALL");
        List<RuleState> states = ruleStates(today);
        boolean hasCnyActivity = states.stream().anyMatch(state ->
                !state.projection().daily().isEmpty() || !state.missingExecutionNavDates().isEmpty());
        if (!hasCnyActivity) return usdOnlyReport(range, usdSummary);

        LocalDate firstFxDate = firstCnyRelevantDate(states, today);
        TreeMap<LocalDate, BigDecimal> fxRates = fxRates(firstFxDate, today);

        List<PortfolioPerformanceSource.ExternalCashFlow> fundFlows = new ArrayList<>();
        boolean flowComplete = states.stream().allMatch(state -> state.missingExecutionNavDates().isEmpty());
        for (RuleState state : states) {
            for (AutoDcaDtos.DailyExecutionResponse execution : state.projection().daily()) {
                BigDecimal rate = fxRate(fxRates, execution.navDate());
                if (rate == null || rate.signum() <= 0) {
                    flowComplete = false;
                    continue;
                }
                fundFlows.add(new PortfolioPerformanceSource.ExternalCashFlow(execution.navDate(),
                        execution.grossAmount().divide(rate, MC)));
            }
        }
        fundFlows.sort(Comparator.comparing(PortfolioPerformanceSource.ExternalCashFlow::date));
        final boolean reportingFlowComplete = flowComplete;

        TreeMap<LocalDate, BigDecimal> cumulativeFundFlow = cumulativeFlows(fundFlows);
        TreeMap<LocalDate, PortfolioDtos.HistoryPoint> usdByDate = new TreeMap<>();
        usdHistory.forEach(point -> usdByDate.put(point.date(), point));

        TreeSet<LocalDate> valuationDates = new TreeSet<>(usdByDate.keySet());
        valuationDates.addAll(fxRates.keySet());
        states.forEach(state -> {
            valuationDates.addAll(state.navByDate().keySet());
            valuationDates.addAll(state.cumulativeShares().keySet());
            valuationDates.addAll(state.openDates());
            valuationDates.addAll(state.missingExecutionNavDates());
        });
        if (valuationDates.isEmpty()) valuationDates.add(today);

        List<PortfolioPerformanceSource.DailyValuation> combinedHistory = new ArrayList<>();
        for (LocalDate date : valuationDates) {
            Map.Entry<LocalDate, PortfolioDtos.HistoryPoint> usdEntry = usdByDate.floorEntry(date);
            BigDecimal usdValue = usdEntry == null || usdEntry.getValue().marketValue() == null
                    ? BigDecimal.ZERO : usdEntry.getValue().marketValue();
            BigDecimal usdFlow = usdEntry == null || usdEntry.getValue().netInvested() == null
                    ? BigDecimal.ZERO : usdEntry.getValue().netInvested();
            FreshnessStatus status = usdEntry == null || usdValue.signum() == 0
                    ? FreshnessStatus.FRESH : usdEntry.getValue().status();

            Valuation fundValuation = fundValuation(states, fxRates, date);
            if (!fundValuation.complete()) status = FreshnessStatus.PARTIAL;
            BigDecimal totalValue = fundValuation.valueUsd() == null ? null : usdValue.add(fundValuation.valueUsd(), MC);
            BigDecimal fundFlow = floor(cumulativeFundFlow, date);
            BigDecimal cumulativeFlow = !reportingFlowComplete
                    ? null : usdFlow.add(fundFlow == null ? BigDecimal.ZERO : fundFlow, MC);
            if (!reportingFlowComplete) status = FreshnessStatus.PARTIAL;
            combinedHistory.add(new PortfolioPerformanceSource.DailyValuation(date, totalValue, cumulativeFlow, status));
        }

        FxRateEntity currentRate = currentFxRate(today);
        Valuation currentFunds = fundValuation(states, fxRates, today);
        boolean currentFundFactsComplete = states.stream().allMatch(state -> fundFactsCompleteOn(state, today));
        BigDecimal cnyValue = currentFundFactsComplete ? currentFundValueCny(states, today) : null;
        BigDecimal cnyValueUsd = currentFunds.valueUsd();
        BigDecimal combinedValue = cnyValueUsd == null || usdSummary.marketValue() == null
                ? null : usdSummary.marketValue().add(cnyValueUsd, MC);
        BigDecimal fundExternalFlow = floor(cumulativeFundFlow, today);
        if (fundExternalFlow == null) fundExternalFlow = BigDecimal.ZERO;
        BigDecimal combinedExternalFlow = reportingFlowComplete && usdSummary.netInvested() != null
                ? usdSummary.netInvested().add(fundExternalFlow, MC) : null;
        BigDecimal combinedPnl = combinedValue == null || combinedExternalFlow == null
                ? null : combinedValue.subtract(combinedExternalFlow, MC);
        FreshnessStatus currentStatus = usdSummary.status() == FreshnessStatus.FRESH
                && currentFunds.complete() && reportingFlowComplete ? FreshnessStatus.FRESH : FreshnessStatus.PARTIAL;

        List<MultiCurrencyDtos.FundPosition> positions = currentFundFactsComplete
                ? currentFundPositions(states, fxRates, today)
                : List.of();
        MultiCurrencyDtos.Summary summary = new MultiCurrencyDtos.Summary(
                FxService.USD, usdSummary.marketValue(), cnyValue, cnyValueUsd, combinedValue,
                usdSummary.netInvested(), reportingFlowComplete ? fundExternalFlow : null,
                combinedExternalFlow, combinedPnl,
                currentRate == null ? null : currentRate.getRate(), currentRate == null ? null : currentRate.getRateDate(),
                currentStatus, usdSummary.asOf(), positions);

        List<PortfolioPerformanceSource.ExternalCashFlow> allFlows = new ArrayList<>(usdPerformanceSource.externalCashFlows());
        if (reportingFlowComplete) allFlows.addAll(fundFlows);
        allFlows.sort(Comparator.comparing(PortfolioPerformanceSource.ExternalCashFlow::date));

        BigDecimal finalFundExternalFlow = fundExternalFlow;
        PortfolioPerformanceSource source = new PortfolioPerformanceSource() {
            @Override public List<DailyValuation> regularCloseHistory() { return List.copyOf(combinedHistory); }
            @Override public CurrentValuation current() {
                BigDecimal cumulative = reportingFlowComplete && usdSummary.netInvested() != null
                        ? usdSummary.netInvested().add(finalFundExternalFlow, MC) : null;
                return new CurrentValuation(today, usdSummary.asOf(), combinedValue, cumulative, currentStatus);
            }
            @Override public List<ExternalCashFlow> externalCashFlows() {
                return reportingFlowComplete ? List.copyOf(allFlows) : List.of();
            }
            @Override public String externalFlowModel() { return FLOW_MODEL; }
        };
        return new MultiCurrencyDtos.Response(summary, new PerformanceEngine(source).performance(range));
    }

    private MultiCurrencyDtos.Response usdOnlyReport(String range, PortfolioDtos.SummaryResponse usdSummary) {
        MultiCurrencyDtos.Summary summary = new MultiCurrencyDtos.Summary(
                FxService.USD,
                usdSummary.marketValue(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                usdSummary.marketValue(),
                usdSummary.netInvested(),
                BigDecimal.ZERO,
                usdSummary.netInvested(),
                usdSummary.totalPnl(),
                null,
                null,
                usdSummary.status(),
                usdSummary.asOf(),
                List.of());
        return new MultiCurrencyDtos.Response(summary, new PerformanceEngine(usdPerformanceSource).performance(range));
    }

    private List<RuleState> ruleStates(LocalDate today) {
        Map<UUID, TreeMap<LocalDate, BigDecimal>> navCache = new HashMap<>();
        List<RuleState> result = new ArrayList<>();
        for (AutoDcaDtos.RuleResponse rule : autoDcaService.list()) {
            AutoDcaDtos.ProjectionResponse projection = autoDcaService.projection(
                    rule.id(), AutoDcaProjectionEngine.GroupBy.MONTH, true);
            TreeMap<LocalDate, BigDecimal> nav = navCache.computeIfAbsent(rule.instrumentId(), this::navByDate);
            TreeMap<LocalDate, BigDecimal> shares = new TreeMap<>();
            BigDecimal running = BigDecimal.ZERO;
            for (AutoDcaDtos.DailyExecutionResponse execution : projection.daily()) {
                running = running.add(execution.shares(), MC);
                shares.put(execution.navDate(), running);
            }
            Set<LocalDate> openDates = rule.startDate().isAfter(today)
                    ? Set.of()
                    : profileRepository.findById(rule.instrumentId())
                    .map(profile -> calendarRepository.findAllByCalendarCodeAndMarketDateBetweenOrderByMarketDateAsc(
                                    profile.getCalendarCode(), rule.startDate(), today).stream()
                            .map(item -> item.getMarketDate()).collect(java.util.stream.Collectors.toSet()))
                    .orElse(Set.of());
            LocalDate executionEnd = rule.endDate() == null || rule.endDate().isAfter(today) ? today : rule.endDate();
            Set<LocalDate> missingExecutionNavDates = executionEnd.isBefore(rule.startDate())
                    ? Set.of()
                    : openDates.stream()
                    .filter(date -> !date.isBefore(rule.startDate()) && !date.isAfter(executionEnd))
                    .filter(date -> !nav.containsKey(date))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            result.add(new RuleState(rule, projection, nav, shares, openDates, missingExecutionNavDates));
        }
        return List.copyOf(result);
    }

    private TreeMap<LocalDate, BigDecimal> navByDate(UUID instrumentId) {
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        Map<LocalDate, FundNavDailyEntity> selected = new LinkedHashMap<>();
        navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(instrumentId)
                .forEach(row -> selected.putIfAbsent(row.getNavDate(), row));
        selected.forEach((date, row) -> result.put(date, row.getNav()));
        return result;
    }

    private LocalDate firstCnyRelevantDate(List<RuleState> states, LocalDate fallback) {
        LocalDate first = null;
        for (RuleState state : states) {
            for (AutoDcaDtos.DailyExecutionResponse execution : state.projection().daily()) {
                if (first == null || execution.navDate().isBefore(first)) first = execution.navDate();
            }
            for (LocalDate missing : state.missingExecutionNavDates()) {
                if (first == null || missing.isBefore(first)) first = missing;
            }
        }
        return first == null ? fallback : first;
    }

    private TreeMap<LocalDate, BigDecimal> fxRates(LocalDate startDate, LocalDate endDate) {
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        FxRateEntity prior = fxService.usdCnyOnOrBefore(startDate);
        if (prior != null) result.put(prior.getRateDate(), prior.getRate());
        FxDtos.FxSeriesResponse series = fxService.usdCny(startDate, endDate);
        series.rates().forEach(rate -> result.put(rate.rateDate(), rate.rate()));
        return result;
    }

    private FxRateEntity currentFxRate(LocalDate date) {
        FxRateEntity rate = fxService.usdCnyOnOrBefore(date);
        if (rate == null || rate.getRateDate() == null
                || ChronoUnit.DAYS.between(rate.getRateDate(), date) > MAX_FX_CARRY_DAYS) return null;
        return rate;
    }

    private Valuation fundValuation(List<RuleState> states, TreeMap<LocalDate, BigDecimal> fxRates, LocalDate date) {
        BigDecimal rate = fxRate(fxRates, date);
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (RuleState state : states) {
            if (hasMissingExecutionOnOrBefore(state, date)) {
                complete = false;
                continue;
            }
            BigDecimal shares = floor(state.cumulativeShares(), date);
            if (shares == null || shares.signum() == 0) continue;
            Map.Entry<LocalDate, BigDecimal> navEntry = state.navByDate().floorEntry(date);
            if (navEntry == null || rate == null || rate.signum() <= 0) {
                complete = false;
                continue;
            }
            if (hasUnresolvedNavGapAfter(state, navEntry.getKey(), date)) complete = false;
            BigDecimal valueCny = shares.multiply(navEntry.getValue(), MC);
            total = total.add(valueCny.divide(rate, MC), MC);
        }
        return new Valuation(complete ? total : null, complete);
    }

    private boolean fundFactsCompleteOn(RuleState state, LocalDate date) {
        if (hasMissingExecutionOnOrBefore(state, date)) return false;
        BigDecimal shares = floor(state.cumulativeShares(), date);
        if (shares == null || shares.signum() == 0) return true;
        Map.Entry<LocalDate, BigDecimal> navEntry = state.navByDate().floorEntry(date);
        return navEntry != null && !hasUnresolvedNavGapAfter(state, navEntry.getKey(), date);
    }

    private boolean hasMissingExecutionOnOrBefore(RuleState state, LocalDate date) {
        return state.missingExecutionNavDates().stream().anyMatch(missing -> !missing.isAfter(date));
    }

    private boolean hasUnresolvedNavGapAfter(RuleState state, LocalDate navDate, LocalDate valuationDate) {
        return state.openDates().stream().anyMatch(openDate -> openDate.isAfter(navDate)
                && !openDate.isAfter(valuationDate)
                && !state.navByDate().containsKey(openDate));
    }

    private BigDecimal currentFundValueCny(List<RuleState> states, LocalDate date) {
        BigDecimal total = BigDecimal.ZERO;
        for (RuleState state : states) {
            BigDecimal shares = floor(state.cumulativeShares(), date);
            Map.Entry<LocalDate, BigDecimal> nav = state.navByDate().floorEntry(date);
            if (shares == null || shares.signum() == 0 || nav == null) continue;
            total = total.add(shares.multiply(nav.getValue(), MC), MC);
        }
        return total;
    }

    private List<MultiCurrencyDtos.FundPosition> currentFundPositions(
            List<RuleState> states, TreeMap<LocalDate, BigDecimal> fxRates, LocalDate date) {
        BigDecimal rate = fxRate(fxRates, date);
        Map<String, FundPositionAccumulator> grouped = new LinkedHashMap<>();
        for (RuleState state : states) {
            BigDecimal shares = floor(state.cumulativeShares(), date);
            Map.Entry<LocalDate, BigDecimal> nav = state.navByDate().floorEntry(date);
            if (shares == null || shares.signum() == 0 || nav == null) continue;
            FundPositionAccumulator item = grouped.computeIfAbsent(state.rule().fundCode(), ignored ->
                    new FundPositionAccumulator(state.rule().fundCode(), state.rule().fundName()));
            item.shares = item.shares.add(shares, MC);
            item.nav = nav.getValue();
            item.navDate = nav.getKey();
        }
        return grouped.values().stream().map(item -> {
            BigDecimal cny = item.shares.multiply(item.nav, MC);
            BigDecimal usd = rate == null || rate.signum() <= 0 ? null : cny.divide(rate, MC);
            return new MultiCurrencyDtos.FundPosition(item.code, item.name, item.shares, item.nav, item.navDate, cny, usd);
        }).toList();
    }

    private TreeMap<LocalDate, BigDecimal> cumulativeFlows(List<PortfolioPerformanceSource.ExternalCashFlow> flows) {
        TreeMap<LocalDate, BigDecimal> exact = new TreeMap<>();
        flows.forEach(flow -> exact.merge(flow.date(), flow.portfolioAmount(), (a, b) -> a.add(b, MC)));
        TreeMap<LocalDate, BigDecimal> cumulative = new TreeMap<>();
        BigDecimal running = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal> entry : exact.entrySet()) {
            running = running.add(entry.getValue(), MC);
            cumulative.put(entry.getKey(), running);
        }
        return cumulative;
    }

    private static BigDecimal fxRate(TreeMap<LocalDate, BigDecimal> map, LocalDate date) {
        Map.Entry<LocalDate, BigDecimal> entry = map.floorEntry(date);
        if (entry == null || ChronoUnit.DAYS.between(entry.getKey(), date) > MAX_FX_CARRY_DAYS) return null;
        return entry.getValue();
    }

    private static BigDecimal floor(TreeMap<LocalDate, BigDecimal> map, LocalDate date) {
        Map.Entry<LocalDate, BigDecimal> entry = map.floorEntry(date);
        return entry == null ? null : entry.getValue();
    }

    private record RuleState(
            AutoDcaDtos.RuleResponse rule,
            AutoDcaDtos.ProjectionResponse projection,
            TreeMap<LocalDate, BigDecimal> navByDate,
            TreeMap<LocalDate, BigDecimal> cumulativeShares,
            Set<LocalDate> openDates,
            Set<LocalDate> missingExecutionNavDates) { }

    private record Valuation(BigDecimal valueUsd, boolean complete) { }

    private static final class FundPositionAccumulator {
        private final String code;
        private final String name;
        private BigDecimal shares = BigDecimal.ZERO;
        private BigDecimal nav;
        private LocalDate navDate;

        private FundPositionAccumulator(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }
}
