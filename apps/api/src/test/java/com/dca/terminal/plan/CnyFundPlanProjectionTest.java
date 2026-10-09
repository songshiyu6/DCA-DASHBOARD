package com.dca.terminal.plan;

import com.dca.terminal.fund.AutoDcaDtos;
import com.dca.terminal.fund.AutoDcaFrequency;
import com.dca.terminal.fund.AutoDcaProjectionEngine;
import com.dca.terminal.fund.AutoDcaService;
import com.dca.terminal.fund.FundMarketCalendarDayEntity;
import com.dca.terminal.fund.FundMarketCalendarDayRepository;
import com.dca.terminal.fund.FundProfileEntity;
import com.dca.terminal.fund.FundProfileRepository;
import com.dca.terminal.fund.FundPurchaseEntity;
import com.dca.terminal.fund.FundPurchaseRepository;
import com.dca.terminal.fx.FxDtos;
import com.dca.terminal.fx.FxRateEntity;
import com.dca.terminal.fx.FxService;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CnyFundPlanProjectionTest {
    private static final LocalDate FLOW_DATE = LocalDate.of(2026, 9, 7);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final Instant AS_OF = Instant.parse("2026-09-08T14:00:00Z");

    @Mock AutoDcaService autoDcaService;
    @Mock FundPurchaseRepository purchaseRepository;
    @Mock FundNavDailyRepository navRepository;
    @Mock FundProfileRepository profileRepository;
    @Mock FundMarketCalendarDayRepository calendarRepository;
    @Mock FxService fxService;

    private CnyFundPlanProjection service;

    @BeforeEach
    void setUp() {
        service = new CnyFundPlanProjection(autoDcaService, purchaseRepository, navRepository,
                profileRepository, calendarRepository, fxService);
        when(purchaseRepository.findAllByOrderByPurchaseDateAscCreatedAtAscIdAsc()).thenReturn(List.of());
    }

    @Test
    void allCnyDcaCountsTowardsUsdBudgetButOnlyNasdaqMapsToQqqmAndOneTimeIsExposureOnly() {
        UUID nasdaqId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        AutoDcaDtos.RuleResponse nasdaq = rule(UUID.randomUUID(), nasdaqId,
                "019547", "广发纳斯达克100指数(QDII)", "720");
        AutoDcaDtos.RuleResponse other = rule(UUID.randomUUID(), otherId,
                "000001", "沪深300指数", "360");

        when(autoDcaService.list()).thenReturn(List.of(nasdaq, other));
        when(autoDcaService.projection(nasdaq.id(), AutoDcaProjectionEngine.GroupBy.MONTH, true))
                .thenReturn(projection(nasdaq, "720", "100"));
        when(autoDcaService.projection(other.id(), AutoDcaProjectionEngine.GroupBy.MONTH, true))
                .thenReturn(projection(other, "360", "100"));
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(nasdaqId))
                .thenReturn(List.of(nav("2026-09-07", "7.20"), nav("2026-09-08", "7.92")));
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(otherId))
                .thenReturn(List.of(nav("2026-09-07", "3.60"), nav("2026-09-08", "3.96")));
        when(profileRepository.findById(nasdaqId)).thenReturn(Optional.empty());
        when(profileRepository.findById(otherId)).thenReturn(Optional.empty());
        fxRates("7.20");

        FundPurchaseEntity oneTime = mock(FundPurchaseEntity.class);
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(oneTime.getPurchaseDate()).thenReturn(FLOW_DATE);
        when(oneTime.getNav()).thenReturn(new BigDecimal("7.20"));
        when(oneTime.getShares()).thenReturn(new BigDecimal("50"));
        when(oneTime.getInstrument()).thenReturn(instrument);
        when(instrument.getId()).thenReturn(nasdaqId);
        when(purchaseRepository.findAllByOrderByPurchaseDateAscCreatedAtAscIdAsc())
                .thenReturn(List.of(oneTime));

        CnyFundPlanProjection.Snapshot result = service.snapshot(TODAY);

        assertThat(result.month(SEPTEMBER).executedUsd()).isEqualByComparingTo("150");
        assertThat(result.month(SEPTEMBER).byEtfUsd()).containsOnlyKeys("QQQM");
        assertThat(result.month(SEPTEMBER).byEtfUsd().get("QQQM")).isEqualByComparingTo("100");
        assertThat(result.exposureByEtfUsd().get("QQQM")).isEqualByComparingTo("165");
        assertThat(result.month(SEPTEMBER).complete()).isTrue();
        assertThat(result.unavailableExposureSymbols()).isEmpty();
    }

    @Test
    void missingHistoricalFxDoesNotCreateFictitiousDcaExecution() {
        UUID nasdaqId = UUID.randomUUID();
        AutoDcaDtos.RuleResponse nasdaq = rule(UUID.randomUUID(), nasdaqId,
                "019547", "纳指100基金", "720");
        when(autoDcaService.list()).thenReturn(List.of(nasdaq));
        when(autoDcaService.projection(nasdaq.id(), AutoDcaProjectionEngine.GroupBy.MONTH, true))
                .thenReturn(projection(nasdaq, "720", "100"));
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(nasdaqId))
                .thenReturn(List.of(nav("2026-09-07", "7.20")));
        when(profileRepository.findById(nasdaqId)).thenReturn(Optional.empty());
        // FX first available on September 8, after the September 7 contribution.
        when(fxService.usdCny(LocalDate.of(2026, 9, 7), TODAY))
                .thenReturn(new FxDtos.FxSeriesResponse("USD", "CNY", FxService.USD_CNY_SEMANTICS,
                        List.of(rate("2026-09-08", "7.20"))));
        when(fxService.usdCnyOnOrBefore(TODAY)).thenReturn(fx("2026-09-08", "7.20"));

        CnyFundPlanProjection.Snapshot result = service.snapshot(TODAY);

        assertThat(result.month(SEPTEMBER).executedUsd()).isEqualByComparingTo("0");
        assertThat(result.month(SEPTEMBER).complete()).isFalse();
        assertThat(result.exposureByEtfUsd().get("QQQM")).isEqualByComparingTo("100");
    }

    @Test
    void missingConfirmedOpenDayNavMarksMonthPartialButNeverInventsAnExecution() {
        UUID nasdaqId = UUID.randomUUID();
        AutoDcaDtos.RuleResponse nasdaq = rule(UUID.randomUUID(), nasdaqId,
                "019547", "纳斯达克100指数", "720");
        FundProfileEntity profile = mock(FundProfileEntity.class);
        FundMarketCalendarDayEntity openMonday = mock(FundMarketCalendarDayEntity.class);
        FundMarketCalendarDayEntity openTuesday = mock(FundMarketCalendarDayEntity.class);
        when(autoDcaService.list()).thenReturn(List.of(nasdaq));
        when(autoDcaService.projection(nasdaq.id(), AutoDcaProjectionEngine.GroupBy.MONTH, true))
                .thenReturn(projection(nasdaq, "720", "100"));
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(nasdaqId))
                .thenReturn(List.of(nav("2026-09-07", "7.20")));
        when(profileRepository.findById(nasdaqId)).thenReturn(Optional.of(profile));
        when(profile.getCalendarCode()).thenReturn("CN_SSE");
        when(calendarRepository.findAllByCalendarCodeAndMarketDateBetweenOrderByMarketDateAsc(
                "CN_SSE", FLOW_DATE, TODAY)).thenReturn(List.of(openMonday, openTuesday));
        when(openMonday.getMarketDate()).thenReturn(FLOW_DATE);
        when(openTuesday.getMarketDate()).thenReturn(TODAY);
        fxRates("7.20");

        CnyFundPlanProjection.Snapshot result = service.snapshot(TODAY);

        assertThat(result.month(SEPTEMBER).executedUsd()).isEqualByComparingTo("100");
        assertThat(result.month(SEPTEMBER).complete()).isFalse();
        assertThat(result.staleExposureSymbols()).containsExactly("QQQM");
    }

    @Test
    void unrelatedFundNameDoesNotMapToQqqm() {
        assertThat(CnyFundPlanProjection.mappedEtf("沪深300指数")).isNull();
        assertThat(CnyFundPlanProjection.mappedEtf("广发纳斯达克100指数(QDII)")).isEqualTo("QQQM");
        assertThat(CnyFundPlanProjection.mappedEtf("纳指ETF联接")).isEqualTo("QQQM");
        assertThat(CnyFundPlanProjection.mappedEtf("Nasdaq-100 Fund")).isEqualTo("QQQM");
    }

    private void fxRates(String value) {
        when(fxService.usdCnyOnOrBefore(FLOW_DATE)).thenReturn(fx("2026-09-07", value));
        when(fxService.usdCny(FLOW_DATE, TODAY)).thenReturn(new FxDtos.FxSeriesResponse(
                "USD", "CNY", FxService.USD_CNY_SEMANTICS,
                List.of(rate("2026-09-07", value), rate("2026-09-08", value))));
        when(fxService.usdCnyOnOrBefore(TODAY)).thenReturn(fx("2026-09-08", value));
    }

    private static AutoDcaDtos.RuleResponse rule(UUID id, UUID fundId,
                                                   String fundCode, String name, String gross) {
        return new AutoDcaDtos.RuleResponse(id, fundId, fundCode, name, new BigDecimal(gross),
                "CNY", AutoDcaFrequency.DAILY_FUND_TRADING_DAY, FLOW_DATE, null,
                BigDecimal.ZERO, true, "REWRITE_HISTORY");
    }

    private static AutoDcaDtos.ProjectionResponse projection(AutoDcaDtos.RuleResponse rule,
                                                               String gross, String shares) {
        AutoDcaDtos.DailyExecutionResponse execution = new AutoDcaDtos.DailyExecutionResponse(
                FLOW_DATE, FLOW_DATE, FLOW_DATE, new BigDecimal("7.20"), new BigDecimal(gross),
                BigDecimal.ZERO, new BigDecimal(gross), new BigDecimal(shares),
                AutoDcaProjectionEngine.ConfirmationStatus.CONFIRMED);
        return new AutoDcaDtos.ProjectionResponse(rule, AutoDcaProjectionEngine.GroupBy.MONTH,
                new BigDecimal("7.20"), FLOW_DATE, "OBSERVED_FUND_NAV_DATES",
                List.of(), List.of(execution));
    }

    private static FundNavDailyEntity nav(String date, String value) {
        FundNavDailyEntity item = new FundNavDailyEntity();
        item.setNavDate(LocalDate.parse(date));
        item.setNav(new BigDecimal(value));
        return item;
    }

    private static FxRateEntity fx(String date, String value) {
        FxRateEntity rate = new FxRateEntity();
        rate.setBaseCurrency("USD");
        rate.setQuoteCurrency("CNY");
        rate.setRateDate(LocalDate.parse(date));
        rate.setRate(new BigDecimal(value));
        return rate;
    }

    private static FxDtos.FxRateResponse rate(String date, String value) {
        return new FxDtos.FxRateResponse("USD", "CNY", LocalDate.parse(date),
                new BigDecimal(value), FxService.USD_CNY_SOURCE, AS_OF);
    }
}
