package com.dca.terminal.reporting;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.fund.AutoDcaDtos;
import com.dca.terminal.fund.AutoDcaFrequency;
import com.dca.terminal.fund.AutoDcaProjectionEngine;
import com.dca.terminal.fund.AutoDcaService;
import com.dca.terminal.fund.FundMarketCalendarDayEntity;
import com.dca.terminal.fund.FundMarketCalendarDayRepository;
import com.dca.terminal.fund.FundProfileEntity;
import com.dca.terminal.fund.FundProfileRepository;
import com.dca.terminal.fx.FxDtos;
import com.dca.terminal.fx.FxRateEntity;
import com.dca.terminal.fx.FxService;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import com.dca.terminal.performance.CashLedgerPortfolioPerformanceSource;
import com.dca.terminal.performance.PortfolioPerformanceSource;
import com.dca.terminal.portfolio.PortfolioDtos;
import com.dca.terminal.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiCurrencyReportingServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);
    private static final Instant AS_OF = Instant.parse("2026-09-08T16:00:00Z");

    @Mock PortfolioService portfolioService;
    @Mock CashLedgerPortfolioPerformanceSource usdPerformanceSource;
    @Mock AutoDcaService autoDcaService;
    @Mock FundNavDailyRepository navRepository;
    @Mock FundProfileRepository profileRepository;
    @Mock FundMarketCalendarDayRepository calendarRepository;
    @Mock FxService fxService;

    private MultiCurrencyReportingService service;

    @BeforeEach
    void setUp() {
        service = new MultiCurrencyReportingService(portfolioService, usdPerformanceSource, autoDcaService,
                navRepository, profileRepository, calendarRepository, fxService,
                Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void noCnyActivityReducesExactlyToExistingUsdAccount() {
        when(portfolioService.summary()).thenReturn(usdSummary("1100", "1000", "100"));
        when(portfolioService.history("ALL")).thenReturn(List.of(usdDay("2026-09-07", "1100", "1000")));
        when(autoDcaService.list()).thenReturn(List.of());
        when(usdPerformanceSource.regularCloseHistory()).thenReturn(List.of(
                new PortfolioPerformanceSource.DailyValuation(LocalDate.of(2026, 9, 7),
                        new BigDecimal("1100"), new BigDecimal("1000"), FreshnessStatus.FRESH)));
        when(usdPerformanceSource.current()).thenReturn(new PortfolioPerformanceSource.CurrentValuation(
                TODAY, AS_OF, new BigDecimal("1100"), new BigDecimal("1000"), FreshnessStatus.FRESH));
        when(usdPerformanceSource.externalCashFlows()).thenReturn(List.of(
                new PortfolioPerformanceSource.ExternalCashFlow(LocalDate.of(2026, 9, 1), new BigDecimal("1000"))));
        when(usdPerformanceSource.externalFlowModel()).thenReturn(
                CashLedgerPortfolioPerformanceSource.EXTERNAL_FLOW_MODEL);

        MultiCurrencyDtos.Response result = service.report("ALL");

        assertThat(result.summary().combinedValueUsd()).isEqualByComparingTo("1100");
        assertThat(result.summary().combinedExternalFlowUsd()).isEqualByComparingTo("1000");
        assertThat(result.summary().combinedPnlUsd()).isEqualByComparingTo("100");
        assertThat(result.summary().cnyFundValue()).isEqualByComparingTo("0");
        assertThat(result.summary().cnyFundValueUsd()).isEqualByComparingTo("0");
        assertThat(result.summary().funds()).isEmpty();
        assertThat(result.performance().externalFlowModel())
                .isEqualTo(CashLedgerPortfolioPerformanceSource.EXTERNAL_FLOW_MODEL);
        verify(fxService, never()).usdCnyOnOrBefore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cnyContributionUsesFlowDateFxWhileLaterFxMovesPortfolioValue() {
        UUID instrumentId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        AutoDcaDtos.RuleResponse rule = rule(ruleId, instrumentId);
        AutoDcaDtos.DailyExecutionResponse execution = execution("2026-09-07", "7.10", "710", "100");

        when(portfolioService.summary()).thenReturn(usdSummary("1000", "1000", "0"));
        when(portfolioService.history("ALL")).thenReturn(List.of(usdDay("2026-09-07", "1000", "1000")));
        when(autoDcaService.list()).thenReturn(List.of(rule));
        when(autoDcaService.projection(ruleId, AutoDcaProjectionEngine.GroupBy.MONTH, true))
                .thenReturn(projection(rule, execution));
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(instrumentId))
                .thenReturn(List.of(nav("2026-09-07", "7.10"), nav("2026-09-08", "7.81")));
        when(profileRepository.findById(instrumentId)).thenReturn(Optional.empty());
        when(fxService.usdCnyOnOrBefore(LocalDate.of(2026, 9, 7))).thenReturn(fx("2026-09-07", "7.10"));
        when(fxService.usdCny(LocalDate.of(2026, 9, 7), TODAY)).thenReturn(new FxDtos.FxSeriesResponse(
                "USD", "CNY", FxService.USD_CNY_SEMANTICS,
                List.of(rate("2026-09-07", "7.10"), rate("2026-09-08", "7.20"))));
        when(fxService.usdCnyOnOrBefore(TODAY)).thenReturn(fx("2026-09-08", "7.20"));
        when(usdPerformanceSource.externalCashFlows()).thenReturn(List.of(
                new PortfolioPerformanceSource.ExternalCashFlow(LocalDate.of(2026, 9, 1), new BigDecimal("1000"))));

        MultiCurrencyDtos.Response result = service.report("ALL");

        assertThat(result.summary().cnyAutoDcaExternalFlowUsd()).isEqualByComparingTo("100");
        assertThat(result.summary().combinedExternalFlowUsd()).isEqualByComparingTo("1100");
        assertThat(result.summary().cnyFundValue()).isEqualByComparingTo("781");
        assertThat(result.summary().cnyFundValueUsd()).isEqualByComparingTo("108.4722222222222222222222222222222");
        assertThat(result.summary().combinedValueUsd()).isEqualByComparingTo("1108.472222222222222222222222222222");
        assertThat(result.summary().combinedPnlUsd()).isEqualByComparingTo("8.472222222222222222222222222222");
        assertThat(result.summary().status()).isEqualTo(FreshnessStatus.FRESH);
        assertThat(result.performance().externalFlowModel())
                .isEqualTo("USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY");
        assertThat(result.performance().twr()).isPositive();
    }

    @Test
    void missingFxMakesCombinedValuationPartialInsteadOfInventingAConversion() {
        UUID instrumentId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        AutoDcaDtos.RuleResponse rule = rule(ruleId, instrumentId);
        AutoDcaDtos.DailyExecutionResponse execution = execution("2026-09-07", "7.10", "710", "100");

        when(portfolioService.summary()).thenReturn(usdSummary("1000", "1000", "0"));
        when(portfolioService.history("ALL")).thenReturn(List.of(usdDay("2026-09-07", "1000", "1000")));
        when(autoDcaService.list()).thenReturn(List.of(rule));
        when(autoDcaService.projection(ruleId, AutoDcaProjectionEngine.GroupBy.MONTH, true))
                .thenReturn(projection(rule, execution));
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(instrumentId))
                .thenReturn(List.of(nav("2026-09-07", "7.10"), nav("2026-09-08", "7.20")));
        when(profileRepository.findById(instrumentId)).thenReturn(Optional.empty());
        when(fxService.usdCnyOnOrBefore(LocalDate.of(2026, 9, 7))).thenReturn(null);
        when(fxService.usdCny(LocalDate.of(2026, 9, 7), TODAY)).thenReturn(new FxDtos.FxSeriesResponse(
                "USD", "CNY", FxService.USD_CNY_SEMANTICS, List.of()));
        when(fxService.usdCnyOnOrBefore(TODAY)).thenReturn(null);
        when(usdPerformanceSource.externalCashFlows()).thenReturn(List.of());

        MultiCurrencyDtos.Response result = service.report("ALL");

        assertThat(result.summary().status()).isEqualTo(FreshnessStatus.PARTIAL);
        assertThat(result.summary().cnyFundValueUsd()).isNull();
        assertThat(result.summary().combinedValueUsd()).isNull();
        assertThat(result.summary().cnyAutoDcaExternalFlowUsd()).isNull();
        assertThat(result.summary().combinedExternalFlowUsd()).isNull();
        assertThat(result.summary().combinedPnlUsd()).isNull();
        assertThat(result.performance().liveEndpointIncluded()).isFalse();
    }

    @Test
    void confirmedOpenDayWithoutNavMakesReportingPartialEvenWithoutAnotherMarketEvent() {
        UUID instrumentId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        AutoDcaDtos.RuleResponse rule = rule(ruleId, instrumentId);
        AutoDcaDtos.DailyExecutionResponse execution = execution("2026-09-07", "7.10", "710", "100");
        FundProfileEntity profile = mock(FundProfileEntity.class);
        FundMarketCalendarDayEntity openSep7 = mock(FundMarketCalendarDayEntity.class);
        FundMarketCalendarDayEntity openSep8 = mock(FundMarketCalendarDayEntity.class);

        when(portfolioService.summary()).thenReturn(usdSummary("1000", "1000", "0"));
        when(portfolioService.history("ALL")).thenReturn(List.of(usdDay("2026-09-07", "1000", "1000")));
        when(autoDcaService.list()).thenReturn(List.of(rule));
        when(autoDcaService.projection(ruleId, AutoDcaProjectionEngine.GroupBy.MONTH, true))
                .thenReturn(projection(rule, execution));
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(instrumentId))
                .thenReturn(List.of(nav("2026-09-07", "7.10")));
        when(profile.getCalendarCode()).thenReturn("CN_SSE");
        when(profileRepository.findById(instrumentId)).thenReturn(Optional.of(profile));
        when(openSep7.getMarketDate()).thenReturn(LocalDate.of(2026, 9, 7));
        when(openSep8.getMarketDate()).thenReturn(TODAY);
        when(calendarRepository.findAllByCalendarCodeAndMarketDateBetweenOrderByMarketDateAsc(
                "CN_SSE", LocalDate.of(2026, 9, 7), TODAY)).thenReturn(List.of(openSep7, openSep8));
        when(fxService.usdCnyOnOrBefore(LocalDate.of(2026, 9, 7))).thenReturn(fx("2026-09-07", "7.10"));
        when(fxService.usdCny(LocalDate.of(2026, 9, 7), TODAY)).thenReturn(new FxDtos.FxSeriesResponse(
                "USD", "CNY", FxService.USD_CNY_SEMANTICS,
                List.of(rate("2026-09-07", "7.10"), rate("2026-09-08", "7.20"))));
        when(fxService.usdCnyOnOrBefore(TODAY)).thenReturn(fx("2026-09-08", "7.20"));
        when(usdPerformanceSource.externalCashFlows()).thenReturn(List.of());

        MultiCurrencyDtos.Response result = service.report("ALL");

        assertThat(result.summary().status()).isEqualTo(FreshnessStatus.PARTIAL);
        assertThat(result.summary().cnyFundValue()).isNull();
        assertThat(result.summary().cnyFundValueUsd()).isNull();
        assertThat(result.summary().combinedValueUsd()).isNull();
        assertThat(result.performance().liveEndpointIncluded()).isFalse();
        assertThat(result.performance().points())
                .anyMatch(point -> point.date().equals(TODAY) && point.dataStatus() == FreshnessStatus.PARTIAL);
    }

    private static PortfolioDtos.SummaryResponse usdSummary(String value, String flow, String pnl) {
        return new PortfolioDtos.SummaryResponse(new BigDecimal(value), BigDecimal.ZERO, new BigDecimal(flow),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(pnl), null,
                FreshnessStatus.FRESH, AS_OF, new BigDecimal(value), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static PortfolioDtos.HistoryPoint usdDay(String date, String value, String flow) {
        return new PortfolioDtos.HistoryPoint(LocalDate.parse(date), new BigDecimal(value), new BigDecimal(flow),
                BigDecimal.ZERO, BigDecimal.ZERO, FreshnessStatus.FRESH, new BigDecimal(value), BigDecimal.ZERO);
    }

    private static AutoDcaDtos.RuleResponse rule(UUID ruleId, UUID instrumentId) {
        return new AutoDcaDtos.RuleResponse(ruleId, instrumentId, "000001", "Test Fund", new BigDecimal("710"),
                "CNY", AutoDcaFrequency.DAILY_FUND_TRADING_DAY, LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 7), BigDecimal.ZERO, true, "REWRITE_HISTORY");
    }

    private static AutoDcaDtos.ProjectionResponse projection(
            AutoDcaDtos.RuleResponse rule, AutoDcaDtos.DailyExecutionResponse execution) {
        return new AutoDcaDtos.ProjectionResponse(rule, AutoDcaProjectionEngine.GroupBy.MONTH,
                execution.nav(), execution.navDate(), "OBSERVED_FUND_NAV_DATES", List.of(), List.of(execution));
    }

    private static AutoDcaDtos.DailyExecutionResponse execution(String date, String nav, String gross, String shares) {
        return new AutoDcaDtos.DailyExecutionResponse(LocalDate.parse(date), LocalDate.parse(date), LocalDate.parse(date),
                new BigDecimal(nav), new BigDecimal(gross), BigDecimal.ZERO, new BigDecimal(gross),
                new BigDecimal(shares), AutoDcaProjectionEngine.ConfirmationStatus.CONFIRMED);
    }

    private static FundNavDailyEntity nav(String date, String value) {
        FundNavDailyEntity entity = new FundNavDailyEntity();
        entity.setNavDate(LocalDate.parse(date));
        entity.setNav(new BigDecimal(value));
        entity.setSource("TEST");
        entity.setRetrievedAt(AS_OF);
        return entity;
    }

    private static FxRateEntity fx(String date, String value) {
        FxRateEntity entity = new FxRateEntity();
        entity.setBaseCurrency("USD");
        entity.setQuoteCurrency("CNY");
        entity.setRateDate(LocalDate.parse(date));
        entity.setRate(new BigDecimal(value));
        entity.setSource(FxService.USD_CNY_SOURCE);
        entity.setRetrievedAt(AS_OF);
        return entity;
    }

    private static FxDtos.FxRateResponse rate(String date, String value) {
        return new FxDtos.FxRateResponse("USD", "CNY", LocalDate.parse(date), new BigDecimal(value),
                FxService.USD_CNY_SOURCE, AS_OF);
    }
}
