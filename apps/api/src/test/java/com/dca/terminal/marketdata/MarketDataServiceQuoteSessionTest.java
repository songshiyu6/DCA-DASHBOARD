package com.dca.terminal.marketdata;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.QuoteLatestEntity;
import com.dca.terminal.marketdata.ProviderModels.ProviderQuote;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.settings.AppSettingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataServiceQuoteSessionTest {
    private static final Instant NOW = Instant.parse("2026-08-31T04:54:28Z");

    @Test
    void storesRegularFallbackAsPartialInsteadOfFresh() {
        Fixture fixture = fixture(new ProviderQuote(new BigDecimal("707.24"), new BigDecimal("708.75"), null, null,
                Instant.parse("2026-08-28T20:00:00Z"), NOW, QuoteSession.REGULAR_FALLBACK));

        var response = fixture.service.latestQuote(fixture.instrument);

        assertEquals(FreshnessStatus.PARTIAL, response.status());
        assertEquals(QuoteSession.REGULAR_FALLBACK, response.quoteSession());
        ArgumentCaptor<QuoteLatestEntity> saved = ArgumentCaptor.forClass(QuoteLatestEntity.class);
        org.mockito.Mockito.verify(fixture.quotes).save(saved.capture());
        assertEquals(FreshnessStatus.PARTIAL, saved.getValue().getStatus());
        assertEquals(QuoteSession.REGULAR_FALLBACK, saved.getValue().getQuoteSession());
    }

    @Test
    void keepsARealOvernightQuoteFresh() {
        Fixture fixture = fixture(new ProviderQuote(new BigDecimal("705.00"), new BigDecimal("707.24"), null, null,
                NOW, NOW, QuoteSession.OVERNIGHT));

        var response = fixture.service.latestQuote(fixture.instrument);

        assertEquals(FreshnessStatus.FRESH, response.status());
        assertEquals(QuoteSession.OVERNIGHT, response.quoteSession());
    }

    private static Fixture fixture(ProviderQuote providerQuote) {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");

        MarketDataProvider yahoo = mock(MarketDataProvider.class);
        when(yahoo.id()).thenReturn(ProviderId.YAHOO);
        when(yahoo.isConfigured()).thenReturn(true);
        when(yahoo.getLatestQuote(instrument)).thenReturn(providerQuote);

        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findById(instrumentId)).thenReturn(Optional.empty());
        when(quotes.save(any(QuoteLatestEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FundNavDailyRepository nav = mock(FundNavDailyRepository.class);
        when(nav.findTopByInstrumentIdOrderByNavDateDesc(instrumentId)).thenReturn(Optional.empty());
        AppSettingRepository settings = mock(AppSettingRepository.class);
        when(settings.findAll()).thenReturn(List.of());
        when(settings.findById(anyString())).thenReturn(Optional.empty());

        MarketDataService service = new MarketDataService(mock(InstrumentRepository.class), mock(PriceDailyRepository.class),
                quotes, mock(SplitEventRepository.class), nav, settings, List.of(yahoo), Clock.fixed(NOW, ZoneOffset.UTC),
                ZoneOffset.UTC, "YAHOO", "NONE", 60, 1, mock(PortfolioSnapshotInvalidator.class));
        return new Fixture(service, instrument, quotes);
    }

    private record Fixture(MarketDataService service, InstrumentEntity instrument, QuoteLatestRepository quotes) { }
}
