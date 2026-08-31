package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YahooFinanceProviderIntradayTest {
    private HttpServer server;
    private YahooFinanceProvider provider;
    private final AtomicReference<URI> lastRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        provider = new YahooFinanceProvider(
                RestClient.builder(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1_000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void emptyCurrentDayChartIsAValidEmptyIntradayResponse() {
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, """
                {
                  "chart": {"result": [{
                    "meta": {"exchangeTimezoneName": "America/New_York"},
                    "indicators": {"quote": [{}]}
                  }], "error": null}
                }
                """));

        List<ProviderModels.IntradayBar> bars = provider.getIntradayPrices(instrument(),
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));

        assertTrue(bars.isEmpty());
    }

    @Test
    void onlyKeepsBarsInsideYahooPreRegularAndPostTradingPeriods() {
        long beforePre = Instant.parse("2026-08-31T07:55:00Z").getEpochSecond();
        long preStart = Instant.parse("2026-08-31T08:00:00Z").getEpochSecond();
        long regularStart = Instant.parse("2026-08-31T13:30:00Z").getEpochSecond();
        long postStart = Instant.parse("2026-08-31T20:00:00Z").getEpochSecond();
        long beforePostEnd = Instant.parse("2026-08-31T23:55:00Z").getEpochSecond();
        long postEnd = Instant.parse("2026-09-01T00:00:00Z").getEpochSecond();
        String body = """
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
                    "timestamp": [%d,%d,%d,%d,%d,%d],
                    "indicators": {"quote": [{
                      "open": [1,2,3,4,5,6],
                      "high": [1,2,3,4,5,6],
                      "low": [1,2,3,4,5,6],
                      "close": [1,2,3,4,5,6],
                      "volume": [1,2,3,4,5,6]
                    }]}
                  }], "error": null}
                }
                """.formatted(preStart, regularStart, regularStart, postStart, postStart, postEnd,
                beforePre, preStart, regularStart, postStart, beforePostEnd, postEnd);
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, body));

        List<ProviderModels.IntradayBar> bars = provider.getIntradayPrices(instrument(),
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));

        assertEquals(List.of(
                        Instant.ofEpochSecond(preStart),
                        Instant.ofEpochSecond(regularStart),
                        Instant.ofEpochSecond(postStart),
                        Instant.ofEpochSecond(beforePostEnd)),
                bars.stream().map(ProviderModels.IntradayBar::timestamp).toList());
    }

    @Test
    void requestsIntradayWindowUsingAmericaNewYorkDayBoundaries() {
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, """
                {
                  "chart": {"result": [{
                    "meta": {"exchangeTimezoneName": "America/New_York"},
                    "indicators": {"quote": [{}]}
                  }], "error": null}
                }
                """));

        provider.getIntradayPrices(instrument(), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));

        assertEquals(String.valueOf(Instant.parse("2026-08-31T04:00:00Z").getEpochSecond()),
                query(lastRequest.get(), "period1"));
        assertEquals(String.valueOf(Instant.parse("2026-09-01T04:00:00Z").getEpochSecond()),
                query(lastRequest.get(), "period2"));
        assertEquals("true", query(lastRequest.get(), "includePrePost"));
        assertEquals("5m", query(lastRequest.get(), "interval"));
    }

    private InstrumentEntity instrument() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        return instrument;
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        lastRequest.set(exchange.getRequestURI());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String query(URI uri, String key) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(key);
    }
}
