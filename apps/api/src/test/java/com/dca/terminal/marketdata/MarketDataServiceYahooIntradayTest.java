package com.dca.terminal.marketdata;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.ProviderModels.IntradayBar;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.settings.AppSettingEntity;
import com.dca.terminal.settings.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataServiceYahooIntradayTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private HttpServer server;
    private final AtomicInteger vooRequests = new AtomicInteger();
    private final AtomicInteger qqqRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void beforePremarketEmptyPrimaryRemainsPartialWithoutRetryOrFallback() {
        server.createContext("/v8/finance/chart/VOO", exchange -> {
            vooRequests.incrementAndGet();
            respond(exchange, emptyChart());
        });
        Instant now = Instant.parse("2026-08-31T07:30:00Z");
        MarketDataProvider yahoo = yahooAt(now);
        MarketDataProvider twelve = configuredFallback();
        when(twelve.getIntradayPrices(any(), any(), any())).thenReturn(List.of(
                new IntradayBar(Instant.parse("2026-08-31T13:30:00Z"), null, null, null,
                        new BigDecimal("700"), 1L)));

        var response = service(now, settings(), yahoo, twelve).prices(instrument("VOO"), "1D");

        assertEquals(FreshnessStatus.PARTIAL, response.status());
        assertEquals("YAHOO", response.source());
        assertNull(response.asOf());
        assertTrue(response.data().isEmpty());
        assertEquals(1, vooRequests.get());
        verify(twelve, times(0)).getIntradayPrices(any(), any(), any());
    }

    @Test
    void regularSessionEmptyPrimaryRetriesThenUsesConfiguredFallback() {
        server.createContext("/v8/finance/chart/VOO", exchange -> {
            vooRequests.incrementAndGet();
            respond(exchange, emptyChart());
        });
        Instant now = Instant.parse("2026-08-31T14:00:00Z");
        MarketDataProvider yahoo = yahooAt(now);
        MarketDataProvider twelve = configuredFallback();
        when(twelve.getIntradayPrices(any(), any(), any())).thenReturn(List.of(
                new IntradayBar(Instant.parse("2026-08-31T13:30:00Z"), null, null, null,
                        new BigDecimal("700"), 1L)));

        var response = service(now, settings(), yahoo, twelve).prices(instrument("VOO"), "1D");

        assertEquals(FreshnessStatus.FRESH, response.status());
        assertEquals("TWELVE_DATA", response.source());
        assertEquals(LocalDate.of(2026, 8, 31), response.asOf());
        assertEquals(1, response.data().size());
        assertEquals(2, vooRequests.get());
        verify(twelve, times(1)).getIntradayPrices(any(), any(), any());
    }

    @Test
    void regularSessionEmptyPrimaryWithoutFallbackIsUnavailable() {
        server.createContext("/v8/finance/chart/VOO", exchange -> {
            vooRequests.incrementAndGet();
            respond(exchange, emptyChart());
        });
        Instant now = Instant.parse("2026-08-31T14:00:00Z");
        MarketDataProvider yahoo = yahooAt(now);

        var response = service(now, settings(new AppSettingEntity("fallbackProvider", "NONE")), yahoo)
                .prices(instrument("VOO"), "1D");

        assertEquals(FreshnessStatus.UNAVAILABLE, response.status());
        assertEquals("YAHOO", response.source());
        assertNull(response.asOf());
        assertTrue(response.data().isEmpty());
        assertTrue(response.message().contains("no fallback"));
        assertEquals(2, vooRequests.get());
    }

    @Test
    void oneSymbolCanFallbackWhileAnotherSymbolUsesNormalYahooBars() {
        server.createContext("/v8/finance/chart/VOO", exchange -> {
            vooRequests.incrementAndGet();
            respond(exchange, emptyChart());
        });
        server.createContext("/v8/finance/chart/QQQ", exchange -> {
            qqqRequests.incrementAndGet();
            respond(exchange, regularBarChart("574.35"));
        });
        Instant now = Instant.parse("2026-08-31T14:00:00Z");
        MarketDataProvider yahoo = yahooAt(now);
        MarketDataProvider twelve = configuredFallback();
        when(twelve.getIntradayPrices(any(), any(), any())).thenAnswer(invocation -> {
            InstrumentEntity instrument = invocation.getArgument(0);
            if (!"VOO".equals(instrument.getSymbol())) return List.of();
            return List.of(new IntradayBar(Instant.parse("2026-08-31T13:30:00Z"), null, null, null,
                    new BigDecimal("620.50"), 1L));
        });
        MarketDataService service = service(now, settings(), yahoo, twelve);

        var failedSymbol = service.prices(instrument("VOO"), "1D");
        var normalSymbol = service.prices(instrument("QQQ"), "1D");

        assertEquals(FreshnessStatus.FRESH, failedSymbol.status());
        assertEquals("TWELVE_DATA", failedSymbol.source());
        assertEquals(FreshnessStatus.FRESH, normalSymbol.status());
        assertEquals("YAHOO", normalSymbol.source());
        assertEquals(2, vooRequests.get());
        assertEquals(1, qqqRequests.get());
        verify(twelve, times(1)).getIntradayPrices(any(), any(), any());
    }

    private MarketDataProvider yahooAt(Instant now) {
        return new YahooFinanceProvider(
                RestClient.builder(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1_000,
                "",
                false,
                "https://fc.yahoo.com",
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private MarketDataProvider configuredFallback() {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.id()).thenReturn(ProviderId.TWELVE_DATA);
        when(provider.isConfigured()).thenReturn(true);
        return provider;
    }

    private MarketDataService service(Instant now, AppSettingRepository settings, MarketDataProvider... providers) {
        return new MarketDataService(mock(InstrumentRepository.class), mock(PriceDailyRepository.class),
                mock(QuoteLatestRepository.class), mock(SplitEventRepository.class), mock(FundNavDailyRepository.class),
                settings, List.of(providers), Clock.fixed(now, ZoneOffset.UTC), NEW_YORK,
                "YAHOO", "TWELVE_DATA", 60, 2, mock(PortfolioSnapshotInvalidator.class));
    }

    private AppSettingRepository settings(AppSettingEntity... values) {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(values));
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        return repository;
    }

    private InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(symbol);
        return instrument;
    }

    private String emptyChart() {
        return """
                {
                  "chart": {"result": [{
                    "meta": {"exchangeTimezoneName": "America/New_York"},
                    "indicators": {"quote": [{}]}
                  }], "error": null}
                }
                """;
    }

    private String regularBarChart(String close) {
        long preStart = Instant.parse("2026-08-31T08:00:00Z").getEpochSecond();
        long regularStart = Instant.parse("2026-08-31T13:30:00Z").getEpochSecond();
        long regularEnd = Instant.parse("2026-08-31T20:00:00Z").getEpochSecond();
        long postEnd = Instant.parse("2026-09-01T00:00:00Z").getEpochSecond();
        return """
                {
                  "chart": {"result": [{
                    "meta": {
                      "exchangeTimezoneName": "America/New_York",
                      "currentTradingPeriod": {
                        "pre": {"start": %d, "end": %d},
                        "regular": {"start": %d, "end": %d},
                        "post": {"start": %d, "end": %d}
                      }
                    },
                    "timestamp": [%d],
                    "indicators": {"quote": [{"close": [%s]}]}
                  }], "error": null}
                }
                """.formatted(preStart, regularStart, regularStart, regularEnd, regularEnd, postEnd,
                regularStart, close);
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
