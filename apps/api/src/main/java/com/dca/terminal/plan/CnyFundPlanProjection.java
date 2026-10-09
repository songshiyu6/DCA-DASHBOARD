package com.dca.terminal.plan;

import com.dca.terminal.fund.AutoDcaDtos;
import com.dca.terminal.fund.AutoDcaProjectionEngine;
import com.dca.terminal.fund.AutoDcaService;
import com.dca.terminal.fund.FundMarketCalendarDayRepository;
import com.dca.terminal.fund.FundProfileRepository;
import com.dca.terminal.fund.FundPurchaseEntity;
import com.dca.terminal.fund.FundPurchaseRepository;
import com.dca.terminal.fx.FxDtos;
import com.dca.terminal.fx.FxRateEntity;
import com.dca.terminal.fx.FxService;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only bridge from CNY auto-DCA facts to the USD plan view.
 * It does not create transactions, link fund activity to real USD cycles, or write cycle state.
 * Only observed NAV executions contribute to a cycle; one-time purchases affect exposure but
 * remain outside recurring-DCA execution amounts.
 */
@Service
public class CnyFundPlanProjection {
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final long MAX_FX_CARRY_DAYS = 7;
    private static final long MAX_FX_SERIES_DAYS = 5_500;

    private final AutoDcaService autoDcaService;
    private final FundPurchaseRepository purchaseRepository;
    private final FundNavDailyRepository navRepository;
    private final FundProfileRepository profileRepository;
    private final FundMarketCalendarDayRepository calendarRepository;
    private final FxService fxService;

    public CnyFundPlanProjection(AutoDcaService autoDcaService,
                                 FundPurchaseRepository purchaseRepository,
                                 FundNavDailyRepository navRepository,
                                 FundProfileRepository profileRepository,
                                 FundMarketCalendarDayRepository calendarRepository,
                                 FxService fxService) {
        this.autoDcaService = autoDcaService;
        this.purchaseRepository = purchaseRepository;
        this.navRepository = navRepository;
        this.profileRepository = profileRepository;
        this.calendarRepository = calendarRepository;
        this.fxService = fxService;
    }

    public record Month(BigDecimal executedUsd, Map<String, BigDecimal> byEtfUsd, boolean complete) {
        public Month {
            byEtfUsd = Map.copyOf(byEtfUsd);
        }
    }

    public record Snapshot(Map<YearMonth, Month> months,
                           Map<String, BigDecimal> exposureByEtfUsd,
                           Set<String> unavailableExposureSymbols,
                           Set<String> staleExposureSymbols) {
        public Snapshot {
            months = Map.copyOf(months);
            exposureByEtfUsd = Map.copyOf(exposureByEtfUsd);
            unavailableExposureSymbols = Set.copyOf(unavailableExposureSymbols);
            staleExposureSymbols = Set.copyOf(staleExposureSymbols);
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Set.of(), Set.of());
        }

        public Month month(YearMonth period) {
            return months.getOrDefault(period, new Month(BigDecimal.ZERO, Map.of(), true));
        }
    }

    private record Flow(LocalDate date, BigDecimal grossCny, String mappedEtf) { }

    private static final class MonthTotals {
        BigDecimal executedUsd = BigDecimal.ZERO;
        Map<String, BigDecimal> byEtfUsd = new HashMap<>();
        boolean complete = true;
    }

    private static final class Position {
        final UUID instrumentId;
        final String mappedEtf;
        BigDecimal shares = BigDecimal.ZERO;

        Position(UUID instrumentId, String mappedEtf) {
            this.instrumentId = instrumentId;
            this.mappedEtf = mappedEtf;
        }

        void addShares(BigDecimal more) {
            shares = shares.add(more, MC);
        }
    }

    /** An explicit reporting equivalence, not a claim that QDII shares are QQQM shares. */
    public static String mappedEtf(String fundName) {
        if (fundName == null) return null;
        String name = fundName.toUpperCase(Locale.ROOT);
        return name.contains("纳指") || name.contains("纳斯达克") || name.contains("NASDAQ")
                ? "QQQM" : null;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(LocalDate asOf) {
        Map<YearMonth, MonthTotals> months = new HashMap<>();
        Map<UUID, Position> positions = new LinkedHashMap<>();
        Map<UUID, TreeMap<LocalDate, BigDecimal>> navCache = new HashMap<>();
        Set<String> staleExposure = new HashSet<>();
        List<Flow> flows = new java.util.ArrayList<>();

        for (AutoDcaDtos.RuleResponse rule : autoDcaService.list()) {
            if (!"CNY".equals(rule.currency()) || rule.startDate().isAfter(asOf)) continue;
            String mapped = mappedEtf(rule.fundName());
            AutoDcaDtos.ProjectionResponse projection = autoDcaService.projection(
                    rule.id(), AutoDcaProjectionEngine.GroupBy.MONTH, true);
            for (AutoDcaDtos.DailyExecutionResponse execution : projection.daily()) {
                if (execution.navDate().isAfter(asOf)) continue;
                flows.add(new Flow(execution.navDate(), execution.grossAmount(), mapped));
                positions.computeIfAbsent(rule.instrumentId(),
                        id -> new Position(id, mapped)).addShares(execution.shares());
            }

            LocalDate end = rule.endDate() == null || rule.endDate().isAfter(asOf)
                    ? asOf : rule.endDate();
            if (end.isBefore(rule.startDate())) continue;
            TreeMap<LocalDate, BigDecimal> nav = navCache.computeIfAbsent(
                    rule.instrumentId(), this::navByDate);
            profileRepository.findById(rule.instrumentId()).ifPresent(profile -> {
                calendarRepository.findAllByCalendarCodeAndMarketDateBetweenOrderByMarketDateAsc(
                        profile.getCalendarCode(), rule.startDate(), end).forEach(day -> {
                    LocalDate date = day.getMarketDate();
                    if (!nav.containsKey(date)) {
                        months.computeIfAbsent(YearMonth.from(date), ignored -> new MonthTotals()).complete = false;
                        if (mapped != null) staleExposure.add(mapped);
                    }
                });
            });
        }

        // Confirmed one-time CNY fund purchases are holdings, not automatic monthly DCA executions.
        for (FundPurchaseEntity purchase : purchaseRepository.findAllByOrderByPurchaseDateAscCreatedAtAscIdAsc()) {
            if (purchase.getPurchaseDate().isAfter(asOf) || purchase.getNav() == null
                    || purchase.getShares() == null) continue;
            UUID id = purchase.getInstrument().getId();
            positions.computeIfAbsent(id,
                    ignored -> new Position(id, mappedEtf(purchase.getInstrument().getName())))
                    .addShares(purchase.getShares());
        }

        TreeMap<LocalDate, BigDecimal> rates = new TreeMap<>();
        if (!flows.isEmpty()) {
            LocalDate first = flows.stream().map(Flow::date).min(LocalDate::compareTo).orElseThrow();
            if (ChronoUnit.DAYS.between(first, asOf) <= MAX_FX_SERIES_DAYS) {
                addRate(rates, fxService.usdCnyOnOrBefore(first));
                FxDtos.FxSeriesResponse series = fxService.usdCny(first, asOf);
                series.rates().forEach(rate -> rates.put(rate.rateDate(), rate.rate()));
            } else {
                // FxService bounds history requests. Very old facts use indexed local lookups.
                for (Flow flow : flows) addRate(rates, fxService.usdCnyOnOrBefore(flow.date()));
            }
        }
        addRate(rates, fxService.usdCnyOnOrBefore(asOf));

        for (Flow flow : flows) {
            MonthTotals month = months.computeIfAbsent(YearMonth.from(flow.date()),
                    ignored -> new MonthTotals());
            BigDecimal rate = rateOnOrBefore(rates, flow.date());
            if (rate == null) {
                month.complete = false;
                continue;
            }
            BigDecimal usd = flow.grossCny().divide(rate, MC);
            month.executedUsd = month.executedUsd.add(usd, MC);
            if (flow.mappedEtf() != null) {
                month.byEtfUsd.merge(flow.mappedEtf(), usd, (left, right) -> left.add(right, MC));
            }
        }

        Map<String, BigDecimal> exposure = new HashMap<>();
        Set<String> unavailableExposure = new HashSet<>();
        BigDecimal valuationRate = rateOnOrBefore(rates, asOf);
        for (Position position : positions.values()) {
            if (position.mappedEtf == null || position.shares.signum() == 0) continue;
            Map.Entry<LocalDate, BigDecimal> latest = navCache.computeIfAbsent(
                    position.instrumentId, this::navByDate).floorEntry(asOf);
            if (valuationRate == null || latest == null) {
                unavailableExposure.add(position.mappedEtf);
                continue;
            }
            BigDecimal usd = position.shares.multiply(latest.getValue(), MC).divide(valuationRate, MC);
            exposure.merge(position.mappedEtf, usd, (left, right) -> left.add(right, MC));
        }

        Map<YearMonth, Month> immutableMonths = new HashMap<>();
        months.forEach((period, month) -> immutableMonths.put(period,
                new Month(month.executedUsd, month.byEtfUsd, month.complete)));
        return new Snapshot(immutableMonths, exposure, unavailableExposure, staleExposure);
    }

    private TreeMap<LocalDate, BigDecimal> navByDate(UUID instrumentId) {
        TreeMap<LocalDate, BigDecimal> nav = new TreeMap<>();
        for (FundNavDailyEntity row : navRepository
                .findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(instrumentId)) {
            if (row.getNav() != null && row.getNav().signum() > 0) {
                nav.putIfAbsent(row.getNavDate(), row.getNav());
            }
        }
        return nav;
    }

    private static void addRate(TreeMap<LocalDate, BigDecimal> rates, FxRateEntity rate) {
        if (rate != null && rate.getRateDate() != null && rate.getRate() != null
                && rate.getRate().signum() > 0) {
            rates.put(rate.getRateDate(), rate.getRate());
        }
    }

    private static BigDecimal rateOnOrBefore(TreeMap<LocalDate, BigDecimal> rates, LocalDate date) {
        Map.Entry<LocalDate, BigDecimal> entry = rates.floorEntry(date);
        if (entry == null || entry.getValue().signum() <= 0
                || ChronoUnit.DAYS.between(entry.getKey(), date) > MAX_FX_CARRY_DAYS) return null;
        return entry.getValue();
    }
}
