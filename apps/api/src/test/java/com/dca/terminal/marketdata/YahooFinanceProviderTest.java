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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static com.dca.terminal.marketdata.ProviderModels.PriceBar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YahooFinanceProviderTest {
    private MockServer server;
    private YahooFinanceProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
        provider = new YahooFinanceProvider(RestClient.builder(), new ObjectMapper(),
                server.baseUrl().toString(), 1_000);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void searchesQqqAndVooFromYahooAutocompleteAndFiltersEtfs() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {
                  "ResultSet": {
                    "Query": "QQQ VOO",
                    "Result": [
                      {"symbol":"QQQ","name":"Invesco QQQ Trust","exch":"NGM","type":"E","exchDisp":"NASDAQ","typeDisp":"ETF"},
                      {"symbol":"VOO","name":"Vanguard S&P 500 ETF","exch":"PCX","type":"E","exchDisp":"NYSEArca","typeDisp":"ETF"},
                      {"symbol":"AAPL","name":"Apple Inc.","exch":"NMS","type":"S","exchDisp":"NASDAQ","typeDisp":"Equity"}
                    ]
                  }
                }
                """));

        List<ProviderModels.ProviderSearchResult> results = provider.search("QQQ");

        assertEquals(List.of("QQQ", "VOO"), results.stream()
                .map(ProviderModels.ProviderSearchResult::symbol).toList());
        assertEquals("Invesco QQQ Trust", results.getFirst().name());
        assertEquals("NASDAQ", results.getFirst().exchange());
        assertEquals("NYSEArca", results.get(1).exchange());
        assertEquals("QQQ", query(server.requests().getFirst(), "query"));
        assertEquals("en-US", query(server.requests().getFirst(), "lang"));
        assertEquals("US", query(server.requests().getFirst(), "region"));
        assertEquals("Mozilla/5.0", server.userAgents().getFirst());
    }

    @Test
    void returnsEmptyForAValidAutocompleteResponseWithoutEtfs() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {"ResultSet":{"Query":"DOES-NOT-EXIST","Result":[]}}
                """));

        assertTrue(provider.search("DOES-NOT-EXIST").isEmpty());
    }

    @Test
    void marksRateLimitAsRetryable() {
        server.when("/v6/finance/autocomplete", uri -> new Response(429, "Too Many Requests"));

        ProviderException exception = assertThrows(ProviderException.class, () -> provider.search("QQQ"));

        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("429"));
    }

    @Test
    void parsesYahooDailyChartWithAdjustedCloseAndDateBoundaries() {
        server.when("/v8/finance/chart/VOO", uri -> new Response(200, """
                {
                  "chart": {"result": [{
                    "meta": {"symbol":"VOO"},
                    "timestamp": [%d, %d, %d],
                    "indicators": {
                      "quote": [{
                        "open": [499, 509, 519], "high": [505, 515, 525],
                        "low": [495, 505, 515], "close": [500, 510, 520],
                        "volume": [100, 200, 300]
                      }],
                      "adjclose": [{"adjclose": [498, 508, 518]}]
                    }
                  }], "error": null}
                }
                """.formatted(
                epoch("2026-08-25T13:30:00Z"),
                epoch("2026-08-26T13:30:00Z"),
                epoch("2026-08-27T13:30:00Z"))));
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");

        List<PriceBar> bars = provider.getHistoricalPrices(instrument,
                LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 27));

        assertEquals(2, bars.size());
        assertEquals(LocalDate.of(2026, 8, 26), bars.getFirst().tradeDate());
        assertEquals(new java.math.BigDecimal("508"), bars.getFirst().adjustedClose());
        assertEquals(new java.math.BigDecimal("520"), bars.getLast().close());
        assertEquals("1d", query(server.requests().getFirst(), "interval"));
        assertTrue(query(server.requests().getFirst(), "period1") != null);
        assertTrue(query(server.requests().getFirst(), "period2") != null);
    }

    @Test
    void rejectsAnEmptyYahooDailyChart() {
        server.when("/v8/finance/chart/VOO", uri -> new Response(200,
                "{\"chart\":{\"result\":[],\"error\":null}}"));
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");

        assertThrows(ProviderException.class, () -> provider.getHistoricalPrices(instrument,
                LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 27)));
    }

    @Test
    void preservesNullWhenYahooAdjustedSeriesHasNoEndpoint() {
        server.when("/v8/finance/chart/VOO", uri -> new Response(200, """
                {
                  "chart": {"result": [{
                    "meta": {"symbol":"VOO"},
                    "timestamp": [%d],
                    "indicators": {"quote": [{
                      "open": [519], "high": [525], "low": [515],
                      "close": [520], "volume": [300]
                    }]}
                  }], "error": null}
                }
                """.formatted(epoch("2026-08-27T13:30:00Z"))));
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");

        List<PriceBar> bars = provider.getHistoricalPrices(instrument,
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 27));

        assertEquals(1, bars.size());
        assertEquals(new java.math.BigDecimal("520"), bars.getFirst().close());
        assertNull(bars.getFirst().adjustedClose());
    }

    @Test
    void marksRateLimitedDailyChartAsRetryable() {
        server.when("/v8/finance/chart/VOO", uri -> new Response(429, "Too Many Requests"));
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");

        ProviderException exception = assertThrows(ProviderException.class, () -> provider.getHistoricalPrices(instrument,
                LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 27)));

        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("429"));
    }

    @Test
    void preservesCanonicalInstrumentNameWhenProfileContainsOnlyYahooDescription() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        instrument.setName("Vanguard S&P 500 ETF");
        instrument.setExchange("NYSEArca");
        instrument.setCurrency("USD");

        server.when("/v10/finance/quoteSummary/VOO", uri -> new Response(200, """
                {
                  "quoteSummary": {"result": [{
                    "assetProfile": {
                      "longBusinessSummary": "A long fund description that is not its display name",
                      "fundFamily": "Vanguard"
                    },
                    "summaryDetail": {
                      "currency": "USD",
                      "annualReportExpenseRatio": {"raw": 0.0003},
                      "yield": {"raw": 0.012}
                    },
                    "defaultKeyStatistics": {"totalAssets": {"raw": 1000000}}
                  }]}
                }
                """));

        var profile = provider.getProfile(instrument).orElseThrow();

        assertEquals("Vanguard S&P 500 ETF", profile.name());
        assertEquals("Vanguard", profile.issuer());
        assertEquals(0, profile.expenseRatio().compareTo(new java.math.BigDecimal("0.0003")));
    }

    @Test
    void filtersIntradayBarsToTheRequestedExchangeTradingDate() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        String body = """
                {
                  "chart": {"result": [{
                    "meta": {"exchangeTimezoneName": "America/New_York"},
                    "timestamp": [%d, %d, %d],
                    "indicators": {"quote": [{
                      "open": [1, 2, 3], "high": [1, 2, 3], "low": [1, 2, 3],
                      "close": [1, 2, 3], "volume": [10, 20, 30]
                    }]}
                  }], "error": null}
                }
                """.formatted(
                epoch("2026-08-27T00:00:00Z"),
                epoch("2026-08-27T13:30:00Z"),
                epoch("2026-08-28T00:00:00Z"));
        server.when("/v8/finance/chart/VOO", uri -> new Response(200, body));

        List<ProviderModels.IntradayBar> bars = provider.getIntradayPrices(
                instrument, LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 27));

        assertEquals(2, bars.size());
        assertEquals("2026-08-27", bars.get(0).timestamp().atZone(ZoneId.of("America/New_York")).toLocalDate().toString());
        assertEquals(2, bars.get(0).close().intValue());
        assertEquals(3, bars.get(1).close().intValue());
    }

    @Test
    void rejectsMalformedSplitEventInsteadOfLeakingParserFailure() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        server.when("/v8/finance/chart/VOO", uri -> new Response(200, """
                {"chart":{"result":[{"events":{"splits":{"not-a-timestamp":{"numerator":2,"denominator":1}}}}],"error":null}}
                """));

        assertThrows(ProviderException.class, () -> provider.getSplits(
                instrument, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    private static long epoch(String value) {
        return Instant.parse(value).getEpochSecond();
    }

    private static String query(URI uri, String key) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(key);
    }

    private record Response(int status, String body) { }

    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Function<URI, Response>> handlers = new HashMap<>();
        private final List<URI> requests = new CopyOnWriteArrayList<>();
        private final List<String> userAgents = new CopyOnWriteArrayList<>();

        private MockServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void when(String path, Function<URI, Response> handler) {
            handlers.put(path, handler);
        }

        private URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private List<URI> requests() {
            return requests;
        }

        private List<String> userAgents() {
            return userAgents;
        }

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            requests.add(uri);
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            Function<URI, Response> handler = handlers.get(uri.getPath());
            Response response = handler == null ? new Response(404, "not found") : handler.apply(uri);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
