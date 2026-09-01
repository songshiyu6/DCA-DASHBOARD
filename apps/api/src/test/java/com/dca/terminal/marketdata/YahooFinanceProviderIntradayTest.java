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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YahooFinanceProviderIntradayTest {
    private static final Instant BEFORE_PREMARKET = Instant.parse("2026-08-31T07:30:00Z");
    private HttpServer server;
    private YahooFinanceProvider provider;
    private final AtomicReference<URI> lastRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        provider = providerAt(BEFORE_PREMARKET);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void emptyBeforePremarketIsAValidEmptyIntradayResponse() {
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, emptyChart()));

        ProviderModels.IntradayResult result = provider.getIntradayResult(instrument("VOO"),
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));
        List<ProviderModels.IntradayBar> bars = provider.getIntradayPrices(instrument("VOO"),
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));

        assertTrue(bars.isEmpty());
        assertEquals(0, result.rawTimestampCount());
        assertEquals(0, result.dateMatchedCount());
        assertEquals(0, result.tradingPeriodMatchedCount());
        assertEquals(0, result.nonNullCloseCount());
    }

    @Test
    void emptyAfterRegularOpenIsRetryableInsteadOfNormalPartialState() {
        provider = providerAt(Instant.parse("2026-08-31T14:00:00Z"));
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, emptyChart()));

        ProviderException exception = assertThrows(ProviderException.class, () -> provider.getIntradayPrices(
                instrument("VOO"), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31)));

        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("regular session started"));
    }

    @Test
    void timestampsWithNullClosesAfterRegularOpenAreRetryable() {
        provider = providerAt(Instant.parse("2026-08-31T14:00:00Z"));
        long regularStart = Instant.parse("2026-08-31T13:30:00Z").getEpochSecond();
        long regularEnd = Instant.parse("2026-08-31T20:00:00Z").getEpochSecond();
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, """
                {
                  "chart": {"result": [{
                    "meta": {
                      "exchangeTimezoneName": "America/New_York",
                      "currentTradingPeriod": {
                        "regular": {"start": %d, "end": %d}
                      }
                    },
                    "timestamp": [%d],
                    "indicators": {"quote": [{"close": [null]}]}
                  }], "error": null}
                }
                """.formatted(regularStart, regularEnd, regularStart)));

        ProviderModels.IntradayResult diagnostics = provider.getIntradayResult(instrument("VOO"),
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));
        ProviderException exception = assertThrows(ProviderException.class, () -> provider.getIntradayPrices(
                instrument("VOO"), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31)));

        assertEquals(1, diagnostics.rawTimestampCount());
        assertEquals(1, diagnostics.dateMatchedCount());
        assertEquals(1, diagnostics.tradingPeriodMatchedCount());
        assertEquals(0, diagnostics.nonNullCloseCount());
        assertTrue(exception.retryable());
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

        ProviderModels.IntradayResult result = provider.getIntradayResult(instrument("VOO"),
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));

        assertEquals(6, result.rawTimestampCount());
        assertEquals(6, result.dateMatchedCount());
        assertEquals(4, result.tradingPeriodMatchedCount());
        assertEquals(4, result.nonNullCloseCount());
        assertEquals(List.of(
                        Instant.ofEpochSecond(preStart),
                        Instant.ofEpochSecond(regularStart),
                        Instant.ofEpochSecond(postStart),
                        Instant.ofEpochSecond(beforePostEnd)),
                result.bars().stream().map(ProviderModels.IntradayBar::timestamp).toList());
    }

    @Test
    void missingExchangeTimezoneFallsBackToNewYorkInsteadOfDroppingUtcNextDayPostBar() {
        long postStart = Instant.parse("2026-01-05T21:00:00Z").getEpochSecond();
        long postEnd = Instant.parse("2026-01-06T01:00:00Z").getEpochSecond();
        long latePost = Instant.parse("2026-01-06T00:30:00Z").getEpochSecond();
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, """
                {
                  "chart": {"result": [{
                    "meta": {
                      "currentTradingPeriod": {
                        "post": {"start": %d, "end": %d}
                      }
                    },
                    "timestamp": [%d],
                    "indicators": {"quote": [{"close": [100.25]}]}
                  }], "error": null}
                }
                """.formatted(postStart, postEnd, latePost)));

        ProviderModels.IntradayResult result = provider.getIntradayResult(instrument("VOO"),
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));

        assertEquals("America/New_York", result.session().exchangeTimezoneName());
        assertEquals(1, result.rawTimestampCount());
        assertEquals(1, result.dateMatchedCount());
        assertEquals(1, result.tradingPeriodMatchedCount());
        assertEquals(1, result.nonNullCloseCount());
        assertEquals(1, result.bars().size());
    }

    @Test
    void requestsIntradayWindowUsingAmericaNewYorkDayBoundaries() {
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, emptyChart()));

        provider.getIntradayResult(instrument("VOO"), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));

        assertEquals(String.valueOf(Instant.parse("2026-08-31T04:00:00Z").getEpochSecond()),
                query(lastRequest.get(), "period1"));
        assertEquals(String.valueOf(Instant.parse("2026-09-01T04:00:00Z").getEpochSecond()),
                query(lastRequest.get(), "period2"));
        assertEquals("true", query(lastRequest.get(), "includePrePost"));
        assertEquals("5m", query(lastRequest.get(), "interval"));
    }

    private YahooFinanceProvider providerAt(Instant now) {
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
