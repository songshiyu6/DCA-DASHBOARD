package com.dca.terminal.marketdata;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.ProviderModels.IntradayBar;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.settings.AppSettingEntity;
import com.dca.terminal.settings.AppSettingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataServiceIntradayTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Instant OVERNIGHT_NOW = Instant.parse("2026-08-31T07:30:00Z");

    @Test
    void emptyTradingDayIntradayIsPartialNotProviderFailureAndIsNotRapidlyRetried() {
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        InstrumentEntity instrument = instrument();
        when(yahoo.getIntradayPrices(instrument, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of());

        var response = service(Clock.fixed(OVERNIGHT_NOW, ZoneOffset.UTC), settings(), 2, yahoo)
                .prices(instrument, "1D");

        assertEquals(FreshnessStatus.PARTIAL, response.status());
        assertEquals("YAHOO", response.source());
        assertNull(response.asOf());
        assertTrue(response.message().contains("no intraday bars"));
        verify(yahoo, times(1)).getIntradayPrices(instrument,
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));
    }

    @Test
    void emptyPrimaryIntradayDoesNotPretendFallbackBarsAreTheSameMarketState() {
        InstrumentEntity instrument = instrument();
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        MarketDataProvider twelve = provider(ProviderId.TWELVE_DATA);
        when(yahoo.getIntradayPrices(any(), any(), any())).thenReturn(List.of());
        when(twelve.getIntradayPrices(any(), any(), any())).thenReturn(List.of(
                new IntradayBar(Instant.parse("2026-08-31T13:30:00Z"), null, null, null,
                        new BigDecimal("700"), 1L)));

        var response = service(Clock.fixed(OVERNIGHT_NOW, ZoneOffset.UTC), settings(), 2, yahoo, twelve)
                .prices(instrument, "1D");

        assertEquals(FreshnessStatus.PARTIAL, response.status());
        assertEquals("YAHOO", response.source());
        verify(twelve, never()).getIntradayPrices(any(), any(), any());
    }

    @Test
    void retryableYahooFailureUsesConfiguredFallbackAndReportsRealSource() {
        InstrumentEntity instrument = instrument();
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        MarketDataProvider twelve = provider(ProviderId.TWELVE_DATA);
        when(yahoo.getIntradayPrices(any(), any(), any()))
                .thenThrow(new ProviderException(ProviderId.YAHOO, "Yahoo HTTP 429", true));
        when(twelve.getIntradayPrices(any(), any(), any())).thenReturn(List.of(
                new IntradayBar(Instant.parse("2026-08-31T13:30:00Z"), null, null, null,
                        new BigDecimal("700"), 1L)));

        var response = service(Clock.fixed(Instant.parse("2026-08-31T14:00:00Z"), ZoneOffset.UTC),
                settings(), 2, yahoo, twelve).prices(instrument, "1D");

        assertEquals(FreshnessStatus.FRESH, response.status());
        assertEquals("TWELVE_DATA", response.source());
        assertEquals(LocalDate.of(2026, 8, 31), response.asOf());
        verify(yahoo, times(2)).getIntradayPrices(any(), any(), any());
        verify(twelve, times(1)).getIntradayPrices(any(), any(), any());
    }

    @Test
    void providerFailureWithoutFallbackIsExplicitlyDifferentFromNoBars() {
        InstrumentEntity instrument = instrument();
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);
        when(yahoo.getIntradayPrices(any(), any(), any()))
                .thenThrow(new ProviderException(ProviderId.YAHOO, "Yahoo HTTP 429", true));

        var response = service(Clock.fixed(Instant.parse("2026-08-31T14:00:00Z"), ZoneOffset.UTC),
                settings(new AppSettingEntity("fallbackProvider", "NONE")), 2, yahoo)
                .prices(instrument, "1D");

        assertEquals(FreshnessStatus.UNAVAILABLE, response.status());
        assertEquals("YAHOO", response.source());
        assertTrue(response.message().contains("no fallback"));
    }

    @Test
    void weekendReturnsClosedStateWithoutCallingProvider() {
        InstrumentEntity instrument = instrument();
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);

        var response = service(Clock.fixed(Instant.parse("2026-08-29T16:00:00Z"), ZoneOffset.UTC),
                settings(), 2, yahoo).prices(instrument, "1D");

        assertEquals(FreshnessStatus.PARTIAL, response.status());
        assertNull(response.source());
        assertNull(response.asOf());
        assertTrue(response.message().contains("market is closed"));
        verify(yahoo, never()).getIntradayPrices(any(), any(), any());
    }

    @Test
    void observedHolidayReturnsClosedStateWithoutCallingProvider() {
        InstrumentEntity instrument = instrument();
        MarketDataProvider yahoo = provider(ProviderId.YAHOO);

        var response = service(Clock.fixed(Instant.parse("2026-07-03T16:00:00Z"), ZoneOffset.UTC),
                settings(), 2, yahoo).prices(instrument, "1D");

        assertEquals(FreshnessStatus.PARTIAL, response.status());
        assertTrue(response.message().contains("market is closed"));
        verify(yahoo, never()).getIntradayPrices(any(), any(), any());
    }

    private static MarketDataService service(Clock clock, AppSettingRepository settings, int attempts,
                                             MarketDataProvider... providers) {
        return new MarketDataService(mock(InstrumentRepository.class), mock(PriceDailyRepository.class),
                mock(QuoteLatestRepository.class), mock(SplitEventRepository.class), mock(FundNavDailyRepository.class),
                settings, List.of(providers), clock, NEW_YORK, "YAHOO", "TWELVE_DATA", 60, attempts,
                mock(PortfolioSnapshotInvalidator.class));
    }

    private static AppSettingRepository settings(AppSettingEntity... values) {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(values));
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        return repository;
    }

    private static MarketDataProvider provider(ProviderId id) {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.id()).thenReturn(id);
        when(provider.isConfigured()).thenReturn(true);
        return provider;
    }

    private static InstrumentEntity instrument() {
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(UUID.randomUUID());
        when(instrument.getSymbol()).thenReturn("VOO");
        return instrument;
    }
}
