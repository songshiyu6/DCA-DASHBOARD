package com.dca.terminal.fund;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.fund.FundDtos.FundCalendarResponse;
import static com.dca.terminal.fund.FundDtos.FundSyncResponse;

@Service
public class FundDataSyncService {
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CALENDAR_CODE = "CN_FUND";
    private static final int DEFAULT_LOOKBACK_DAYS = 365;
    private static final int MAX_SYNC_DAYS = 5500;

    private final ChinaFundDataProvider provider;
    private final FundService fundService;
    private final FundMarketCalendarDayRepository calendarRepository;
    private final FundNavDailyRepository navRepository;
    private final Clock clock;

    public FundDataSyncService(ChinaFundDataProvider provider,
                               FundService fundService,
                               FundMarketCalendarDayRepository calendarRepository,
                               FundNavDailyRepository navRepository,
                               Clock clock) {
        this.provider = provider;
        this.fundService = fundService;
        this.calendarRepository = calendarRepository;
        this.navRepository = navRepository;
        this.clock = clock;
    }

    @Transactional
    public FundSyncResponse sync(UUID fundId, LocalDate requestedStartDate, LocalDate requestedEndDate) {
        FundProfileEntity profile = fundService.profile(fundId);
        String fundCode = profile.getInstrument().getSymbol();
        if (!fundCode.matches("\\d{6}")) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "FUND_PROVIDER_CODE_UNSUPPORTED",
                    "Automatic NAV sync currently requires a six-digit China fund code");
        }

        DateRange range = range(requestedStartDate, requestedEndDate);
        List<ChinaFundDataProvider.NavPoint> navPoints;
        List<LocalDate> tradingDays;
        try {
            navPoints = provider.navHistory(fundCode, range.startDate(), range.endDate());
            tradingDays = provider.tradingDays(range.startDate(), range.endDate());
        } catch (FundDataProviderException exception) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FUND_DATA_PROVIDER_UNAVAILABLE",
                    "China fund data provider is unavailable: " + exception.getMessage());
        }

        Instant retrievedAt = clock.instant();
        persistCalendar(tradingDays, retrievedAt);
        persistNav(profile, navPoints, retrievedAt);
        profile.getInstrument().setDataProvider(provider.source());

        FundCalendarResponse calendar = calendar(fundId, range.startDate(), range.endDate());
        LocalDate latestNavDate = navRepository.findTopByInstrumentIdOrderByNavDateDesc(fundId)
                .map(FundNavDailyEntity::getNavDate)
                .orElse(null);
        return new FundSyncResponse(fundId, fundCode, provider.source(), range.startDate(), range.endDate(),
                navPoints.size(), tradingDays.size(), calendar.missingNavDates().size(), calendar.missingNavDates(),
                latestNavDate, retrievedAt);
    }

    @Transactional(readOnly = true)
    public FundCalendarResponse calendar(UUID fundId, LocalDate requestedStartDate, LocalDate requestedEndDate) {
        FundProfileEntity profile = fundService.profile(fundId);
        DateRange range = range(requestedStartDate, requestedEndDate);
        List<FundMarketCalendarDayEntity> calendarRows = calendarRepository
                .findAllByCalendarCodeAndMarketDateBetweenOrderByMarketDateAsc(
                        profile.getCalendarCode(), range.startDate(), range.endDate());
        Set<LocalDate> navDates = selectedNavDates(fundId, range.startDate(), range.endDate());
        List<LocalDate> missing = calendarRows.stream()
                .map(FundMarketCalendarDayEntity::getMarketDate)
                .filter(date -> !navDates.contains(date))
                .toList();
        String source = calendarRows.isEmpty() ? null : calendarRows.getLast().getSource();
        return new FundCalendarResponse(profile.getCalendarCode(), range.startDate(), range.endDate(), source,
                !calendarRows.isEmpty(), calendarRows.size(), navDates.size(), missing);
    }

    @Transactional(readOnly = true)
    public List<LocalDate> tradingDays(String calendarCode, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) return List.of();
        return calendarRepository.findAllByCalendarCodeAndMarketDateBetweenOrderByMarketDateAsc(
                        calendarCode, startDate, endDate).stream()
                .map(FundMarketCalendarDayEntity::getMarketDate)
                .toList();
    }

    private void persistCalendar(List<LocalDate> tradingDays, Instant retrievedAt) {
        for (LocalDate date : tradingDays.stream().distinct().sorted().toList()) {
            FundMarketCalendarDayEntity row = calendarRepository
                    .findByCalendarCodeAndMarketDate(CALENDAR_CODE, date)
                    .orElseGet(FundMarketCalendarDayEntity::new);
            row.setCalendarCode(CALENDAR_CODE);
            row.setMarketDate(date);
            row.setSource(provider.source());
            row.setRetrievedAt(retrievedAt);
            calendarRepository.save(row);
        }
    }

    private void persistNav(FundProfileEntity profile, List<ChinaFundDataProvider.NavPoint> navPoints, Instant retrievedAt) {
        UUID instrumentId = profile.getInstrument().getId();
        for (ChinaFundDataProvider.NavPoint point : navPoints) {
            if (point.date() == null || point.nav() == null || point.nav().signum() <= 0) continue;
            FundNavDailyEntity row = navRepository.findByInstrumentIdAndNavDateAndSource(
                            instrumentId, point.date(), provider.source())
                    .orElseGet(FundNavDailyEntity::new);
            row.setInstrument(profile.getInstrument());
            row.setNavDate(point.date());
            row.setNav(point.nav());
            row.setSource(provider.source());
            row.setRetrievedAt(retrievedAt);
            navRepository.save(row);
        }
    }

    private Set<LocalDate> selectedNavDates(UUID fundId, LocalDate startDate, LocalDate endDate) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (FundNavDailyEntity row : navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(fundId)) {
            LocalDate date = row.getNavDate();
            if (!date.isBefore(startDate) && !date.isAfter(endDate)) dates.add(date);
        }
        return dates;
    }

    private DateRange range(LocalDate requestedStartDate, LocalDate requestedEndDate) {
        LocalDate today = LocalDate.now(clock.withZone(CHINA_ZONE));
        LocalDate endDate = requestedEndDate == null ? today : requestedEndDate;
        LocalDate startDate = requestedStartDate == null ? endDate.minusDays(DEFAULT_LOOKBACK_DAYS) : requestedStartDate;
        if (endDate.isAfter(today)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FUND_SYNC_RANGE",
                    "Fund sync end date cannot be in the future");
        }
        if (startDate.isAfter(endDate)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FUND_SYNC_RANGE",
                    "Fund sync start date cannot be after end date");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) > MAX_SYNC_DAYS) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "FUND_SYNC_RANGE_TOO_LARGE",
                    "Fund sync range cannot exceed " + MAX_SYNC_DAYS + " days");
        }
        return new DateRange(startDate, endDate);
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) { }
}
