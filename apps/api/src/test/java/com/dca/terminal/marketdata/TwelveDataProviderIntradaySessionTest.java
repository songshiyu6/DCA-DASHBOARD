package com.dca.terminal.marketdata;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.settings.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TwelveDataProviderIntradaySessionTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/time_series", exchange -> {
            requests.incrementAndGet();
            respond(exchange, "{\"meta\":{\"exchange_timezone\":\"America/New_York\"},\"values\":[]}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void emptyBeforeRegularOpenRemainsAValidEmptyResult() {
        Instant now = Instant.parse("2026-09-01T12:00:00Z");
        TwelveDataProvider provider = providerAt(now);

        var bars = provider.getIntradayPrices(instrument("VOO"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertTrue(bars.isEmpty());
        assertEquals(1, requests.get());
    }

    @Test
    void emptyAfterRegularOpenIsRetryable() {
        Instant now = Instant.parse("2026-09-01T14:00:00Z");
        TwelveDataProvider provider = providerAt(now);

        ProviderException exception = assertThrows(ProviderException.class, () -> provider.getIntradayPrices(
                instrument("VOO"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)));

        assertEquals(ProviderId.TWELVE_DATA, exception.provider());
        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("after the regular session started"));
        assertEquals(1, requests.get());
    }

    @Test
    void postOpenPrimaryFailureAndEmptyFallbackEndsUnavailable() {
        Instant now = Instant.parse("2026-09-01T14:00:00Z");
        MarketDataProvider yahoo = mock(MarketDataProvider.class);
        when(yahoo.id()).thenReturn(ProviderId.YAHOO);
        when(yahoo.isConfigured()).thenReturn(true);
        when(yahoo.getIntradayPrices(any(), any(), any()))
                .thenThrow(new ProviderException(ProviderId.YAHOO, "Yahoo current-session bars unavailable", true));
        TwelveDataProvider twelve = providerAt(now);

        MarketDataService service = service(now, yahoo, twelve);
        var response = service.prices(instrument("VOO"), "1D");

        assertEquals(FreshnessStatus.UNAVAILABLE, response.status());
        assertEquals("TWELVE_DATA", response.source());
        assertNull(response.asOf());
        assertTrue(response.data().isEmpty());
        assertTrue(response.message().contains("all configured providers failed"));
        verify(yahoo, times(1)).getIntradayPrices(any(), any(), any());
        assertEquals(1, requests.get());
    }

    @Test
    void sessionEscalationOnlyAppliesToTheCurrentNewYorkDate() {
        assertTrue(TwelveDataProvider.currentRegularSessionStarted(
                LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T13:30:00Z")));
        assertTrue(!TwelveDataProvider.currentRegularSessionStarted(
                LocalDate.of(2026, 8, 31), Instant.parse("2026-09-01T14:00:00Z")));
    }

    private TwelveDataProvider providerAt(Instant now) {
        return new TwelveDataProvider(
                RestClient.builder(),
                new ObjectMapper(),
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1_000,
                true,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private MarketDataService service(Instant now, MarketDataProvider... providers) {
        AppSettingRepository settings = mock(AppSettingRepository.class);
        when(settings.findAll()).thenReturn(List.of());
        when(settings.findById(anyString())).thenReturn(Optional.empty());
        return new MarketDataService(
                mock(InstrumentRepository.class),
                mock(PriceDailyRepository.class),
                mock(QuoteLatestRepository.class),
                mock(SplitEventRepository.class),
                mock(FundNavDailyRepository.class),
                settings,
                List.of(providers),
                Clock.fixed(now, ZoneOffset.UTC),
                NEW_YORK,
                "YAHOO",
                "TWELVE_DATA",
                60,
                1,
                mock(PortfolioSnapshotInvalidator.class));
    }

    private InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(symbol);
        return instrument;
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
