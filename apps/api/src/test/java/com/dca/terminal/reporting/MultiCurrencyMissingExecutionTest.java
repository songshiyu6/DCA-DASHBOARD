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
import com.dca.terminal.performance.CashLedgerPortfolioPerformanceSource;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiCurrencyMissingExecutionTest {
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
    void pastOpenDaysWithoutAnyNavDoNotDisappearIntoUsdOnlyCompatibility() {
        UUID instrumentId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        AutoDcaDtos.RuleResponse rule = new AutoDcaDtos.RuleResponse(
                ruleId, instrumentId, "000001", "Missing NAV Fund", new BigDecimal("100"), "CNY",
                AutoDcaFrequency.DAILY_FUND_TRADING_DAY, LocalDate.of(2026, 9, 7), TODAY,
                BigDecimal.ZERO, true, "REWRITE_HISTORY");
        AutoDcaDtos.ProjectionResponse emptyProjection = new AutoDcaDtos.ProjectionResponse(
                rule, AutoDcaProjectionEngine.GroupBy.MONTH, null, null,
                "PERSISTED_CN_TRADING_DAYS_WITH_NAV_FALLBACK", List.of(), List.of());
        FundProfileEntity profile = mock(FundProfileEntity.class);
        FundMarketCalendarDayEntity openSep7 = mock(FundMarketCalendarDayEntity.class);
        FundMarketCalendarDayEntity openSep8 = mock(FundMarketCalendarDayEntity.class);

        when(portfolioService.summary()).thenReturn(new PortfolioDtos.SummaryResponse(
                new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                FreshnessStatus.FRESH, AS_OF, new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO));
        when(portfolioService.history("ALL")).thenReturn(List.of(new PortfolioDtos.HistoryPoint(
                LocalDate.of(2026, 9, 7), new BigDecimal("1000"), new BigDecimal("1000"),
                BigDecimal.ZERO, BigDecimal.ZERO, FreshnessStatus.FRESH, new BigDecimal("1000"), BigDecimal.ZERO)));
        when(autoDcaService.list()).thenReturn(List.of(rule));
        when(autoDcaService.projection(ruleId, AutoDcaProjectionEngine.GroupBy.MONTH, true)).thenReturn(emptyProjection);
        when(navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(instrumentId)).thenReturn(List.of());
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
        assertThat(result.summary().cnyAutoDcaExternalFlowUsd()).isNull();
        assertThat(result.summary().combinedValueUsd()).isNull();
        assertThat(result.summary().combinedExternalFlowUsd()).isNull();
        assertThat(result.summary().combinedPnlUsd()).isNull();
        assertThat(result.performance().externalFlowModel())
                .isEqualTo("USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY");
        assertThat(result.performance().liveEndpointIncluded()).isFalse();
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
