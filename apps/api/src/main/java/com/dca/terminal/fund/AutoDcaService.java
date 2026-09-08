package com.dca.terminal.fund;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentType;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.fund.AutoDcaDtos.DailyExecutionResponse;
import static com.dca.terminal.fund.AutoDcaDtos.ProjectionResponse;
import static com.dca.terminal.fund.AutoDcaDtos.RuleRequest;
import static com.dca.terminal.fund.AutoDcaDtos.RuleResponse;
import static com.dca.terminal.fund.AutoDcaDtos.SummaryResponse;

@Service
public class AutoDcaService {
    private final AutoDcaRuleRepository ruleRepository;
    private final FundService fundService;
    private final FundProfileRepository profileRepository;
    private final FundNavDailyRepository navRepository;
    private final FundDataSyncService syncService;

    public AutoDcaService(AutoDcaRuleRepository ruleRepository,
                          FundService fundService,
                          FundProfileRepository profileRepository,
                          FundNavDailyRepository navRepository,
                          FundDataSyncService syncService) {
        this.ruleRepository = ruleRepository;
        this.fundService = fundService;
        this.profileRepository = profileRepository;
        this.navRepository = navRepository;
        this.syncService = syncService;
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list() {
        return ruleRepository.findAllByOrderByStartDateAscIdAsc().stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public RuleResponse get(UUID id) { return response(rule(id)); }

    @Transactional
    public RuleResponse create(@Valid RuleRequest request) {
        AutoDcaRuleEntity entity = new AutoDcaRuleEntity();
        apply(entity, request, true);
        return response(ruleRepository.saveAndFlush(entity));
    }

    @Transactional
    public RuleResponse update(UUID id, @Valid RuleRequest request) {
        AutoDcaRuleEntity entity = rule(id);
        apply(entity, request, false);
        return response(ruleRepository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public ProjectionResponse projection(UUID id, AutoDcaProjectionEngine.GroupBy groupBy, boolean includeDaily) {
        AutoDcaRuleEntity rule = rule(id);
        UUID instrumentId = rule.getInstrument().getId();
        FundProfileEntity profile = profileRepository.findById(instrumentId)
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "FUND_PROFILE_MISSING",
                        "Automatic DCA fund is missing its fund profile"));
        List<AutoDcaProjectionEngine.NavPoint> nav = selectedNav(instrumentId);
        AutoDcaProjectionEngine.GroupBy grouping = groupBy == null ? AutoDcaProjectionEngine.GroupBy.MONTH : groupBy;
        LocalDate calendarEnd = confirmationCalendarEnd(rule, nav);
        List<LocalDate> tradingDays = calendarEnd == null ? List.of()
                : syncService.tradingDays(profile.getCalendarCode(), rule.getStartDate(), calendarEnd);
        AutoDcaProjectionEngine.Projection projection = AutoDcaProjectionEngine.project(
                new AutoDcaProjectionEngine.RuleSpec(rule.getStartDate(), rule.getEndDate(), rule.getAmount(),
                        rule.getPurchaseFeeRate(), profile.getConfirmationTradingDays()), nav, grouping, tradingDays);
        List<DailyExecutionResponse> daily = includeDaily
                ? projection.daily().stream().map(this::dailyResponse).toList()
                : List.of();
        String tradingDaySource = tradingDays.isEmpty()
                ? "OBSERVED_FUND_NAV_DATES"
                : "PERSISTED_CN_TRADING_DAYS_WITH_NAV_FALLBACK";
        return new ProjectionResponse(response(rule), grouping, projection.latestNav(), projection.latestNavDate(),
                tradingDaySource, projection.summaries().stream().map(this::summaryResponse).toList(), daily);
    }

    private void apply(AutoDcaRuleEntity entity, RuleRequest request, boolean creating) {
        validateDates(request.startDate(), request.endDate());
        FundProfileEntity profile = fundService.profileByCode(request.fundCode());
        InstrumentEntity instrument = profile.getInstrument();
        if (instrument.getInstrumentType() != InstrumentType.MUTUAL_FUND || !"CNY".equals(instrument.getCurrency())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "AUTO_DCA_REQUIRES_CNY_FUND",
                    "Automatic fund DCA currently supports CNY mutual funds only");
        }
        entity.setInstrument(instrument);
        entity.setAmount(request.amount());
        entity.setCurrency("CNY");
        entity.setFrequency(AutoDcaFrequency.DAILY_FUND_TRADING_DAY);
        entity.setStartDate(request.startDate());
        entity.setEndDate(request.endDate());
        entity.setPurchaseFeeRate(request.purchaseFeeRate() == null ? BigDecimal.ZERO : request.purchaseFeeRate());
        if (request.enabled() != null) entity.setEnabled(request.enabled());
        else if (creating) entity.setEnabled(true);
    }

    private List<AutoDcaProjectionEngine.NavPoint> selectedNav(UUID instrumentId) {
        Map<LocalDate, FundNavDailyEntity> selected = new LinkedHashMap<>();
        for (FundNavDailyEntity row : navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(instrumentId)) {
            selected.putIfAbsent(row.getNavDate(), row);
        }
        List<AutoDcaProjectionEngine.NavPoint> result = new ArrayList<>();
        selected.values().forEach(row -> result.add(new AutoDcaProjectionEngine.NavPoint(row.getNavDate(), row.getNav())));
        return List.copyOf(result);
    }

    private LocalDate confirmationCalendarEnd(AutoDcaRuleEntity rule, List<AutoDcaProjectionEngine.NavPoint> nav) {
        if (nav.isEmpty()) return null;
        LocalDate latestExecutionDate = nav.getLast().date();
        if (rule.getEndDate() != null && rule.getEndDate().isBefore(latestExecutionDate)) {
            latestExecutionDate = rule.getEndDate();
        }
        return latestExecutionDate.plusDays(30);
    }

    private AutoDcaRuleEntity rule(UUID id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "AUTO_DCA_RULE_NOT_FOUND",
                        "Automatic DCA rule not found"));
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_AUTO_DCA_RULE", "Start date is required");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_AUTO_DCA_RULE",
                    "End date cannot be before start date");
        }
    }

    private RuleResponse response(AutoDcaRuleEntity entity) {
        InstrumentEntity instrument = entity.getInstrument();
        return new RuleResponse(entity.getId(), instrument.getId(), instrument.getSymbol(), instrument.getName(),
                entity.getAmount(), entity.getCurrency(), entity.getFrequency(), entity.getStartDate(), entity.getEndDate(),
                entity.getPurchaseFeeRate(), entity.isEnabled(), "REWRITE_HISTORY");
    }

    private DailyExecutionResponse dailyResponse(AutoDcaProjectionEngine.Execution execution) {
        return new DailyExecutionResponse(execution.orderDate(), execution.navDate(), execution.confirmationDate(),
                execution.nav(), execution.grossAmount(), execution.purchaseFee(), execution.netSubscribedAmount(),
                execution.shares(), execution.status());
    }

    private SummaryResponse summaryResponse(AutoDcaProjectionEngine.Summary summary) {
        return new SummaryResponse(summary.period(), summary.startDate(), summary.endDate(), summary.executionCount(),
                summary.confirmedCount(), summary.grossAmount(), summary.purchaseFees(), summary.netSubscribedAmount(),
                summary.shares(), summary.averageNav(), summary.averageCostPerShare(), summary.latestNav(),
                summary.currentValue(), summary.currentPnl(), summary.returnRate());
    }
}
