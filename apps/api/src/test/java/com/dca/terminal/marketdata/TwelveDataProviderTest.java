package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TwelveDataProviderTest {
    private MockServer server;
    private TwelveDataProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
        provider = new TwelveDataProvider(RestClient.builder(), new ObjectMapper(), "test-key",
                server.baseUrl().toString(), 1_000, true);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void searchesAndFiltersToEtfs() {
        server.when("/symbol_search", uri -> new Response(200, """
                {
                  "data": [
                    {"symbol":"VOO","instrument_name":"Vanguard S&P 500 ETF","exchange":"NYSE Arca","currency":"USD","instrument_type":"ETF"},
                    {"symbol":"AAPL","instrument_name":"Apple Inc","exchange":"NASDAQ","currency":"USD","instrument_type":"Common Stock"},
                    {"symbol":"QQQ","instrument_name":"Invesco QQQ Trust","exchange":"NASDAQ","currency":"USD","instrument_type":"ETF"}
                  ],
                  "status":"ok"
                }
                """));

        List<ProviderModels.ProviderSearchResult> results = provider.search(" voo ");

        assertEquals(List.of("VOO", "QQQ"), results.stream().map(ProviderModels.ProviderSearchResult::symbol).toList());
        assertEquals("Vanguard S&P 500 ETF", results.getFirst().name());
        assertEquals("voo", query(server.requests().getFirst(), "symbol"));
        assertEquals("30", query(server.requests().getFirst(), "outputsize"));
        assertEquals("test-key", query(server.requests().getFirst(), "apikey"));
    }

    @Test
    void mapsQuoteAndUsesLastQuoteTimestamp() {
        server.when("/quote", uri -> new Response(200, """
                {
                  "symbol":"VOO",
                  "close":"521.48000",
                  "previous_close":"519.30000",
                  "timestamp":1724761800,
                  "last_quote_at":1724772600,
                  "status":"ok"
                }
                """));

        ProviderModels.ProviderQuote quote = provider.getLatestQuote(instrument("VOO"));

        assertEquals(new BigDecimal("521.48000"), quote.price());
        assertEquals(new BigDecimal("519.30000"), quote.previousClose());
        assertEquals(Instant.ofEpochSecond(1724772600), quote.marketTimestamp());
        assertNull(quote.bid());
        assertNull(quote.ask());
        assertEquals("ETF", query(server.requests().getFirst(), "type"));
    }

    @Test
    void fetchesRawAndAdjustedDailySeriesAndMergesByDate() {
        server.when("/time_series", uri -> {
            String adjust = query(uri, "adjust");
            if ("all".equals(adjust)) {
                return new Response(200, """
                        {"meta":{"symbol":"VOO","interval":"1day"},"values":[
                          {"datetime":"2026-01-02","close":"100.50000"},
                          {"datetime":"2026-01-05","close":"101.25000"}
                        ],"status":"ok"}
                        """);
            }
            return new Response(200, """
                    {"meta":{"symbol":"VOO","interval":"1day"},"values":[
                      {"datetime":"2026-01-05","open":"100.00","high":"102.00","low":"99.00","close":"101.00","volume":"2000"},
                      {"datetime":"2026-01-02","open":"99.00","high":"101.00","low":"98.00","close":"100.00","volume":"1000"}
                    ],"status":"ok"}
                    """);
        });

        List<ProviderModels.PriceBar> bars = provider.getHistoricalPrices(instrument("VOO"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 6));

        assertEquals(2, bars.size());
        assertEquals(LocalDate.of(2026, 1, 2), bars.getFirst().tradeDate());
        assertEquals(new BigDecimal("100.00"), bars.getFirst().close());
        assertEquals(new BigDecimal("100.50000"), bars.getFirst().adjustedClose());
        assertEquals(1_000L, bars.getFirst().volume());
        assertEquals(new BigDecimal("101.25000"), bars.getLast().adjustedClose());
        assertEquals(2, server.requests().size());
        assertTrue(server.requests().stream().anyMatch(uri -> "none".equals(query(uri, "adjust"))));
        assertTrue(server.requests().stream().anyMatch(uri -> "all".equals(query(uri, "adjust"))));
        assertTrue(server.requests().stream().allMatch(uri -> "1day".equals(query(uri, "interval"))));
    }

    @Test
    void mapsIntradayBarsInExchangeTimezoneAndSortsThem() {
        server.when("/time_series", uri -> new Response(200, """
                {
                  "meta":{"symbol":"VOO","interval":"5min","exchange_timezone":"America/New_York"},
                  "values":[
                    {"datetime":"2026-08-27 09:35:00","open":"521.00","high":"521.50","low":"520.90","close":"521.40","volume":"20"},
                    {"datetime":"2026-08-27 09:30:00","open":"520.80","high":"521.10","low":"520.70","close":"521.00","volume":"10"}
                  ],
                  "status":"ok"
                }
                """));

        List<ProviderModels.IntradayBar> bars = provider.getIntradayPrices(instrument("VOO"),
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 27));

        assertEquals(2, bars.size());
        assertEquals(LocalDate.of(2026, 8, 27).atTime(9, 30)
                .atZone(ZoneId.of("America/New_York")).toInstant(), bars.getFirst().timestamp());
        assertEquals(new BigDecimal("521.40"), bars.getLast().close());
        assertEquals("5min", query(server.requests().getFirst(), "interval"));
        assertEquals("America/New_York", query(server.requests().getFirst(), "timezone"));
        assertEquals("none", query(server.requests().getFirst(), "adjust"));
    }

    @Test
    void mapsEtfFullDataProfile() {
        server.when("/etfs/world", uri -> new Response(200, """
                {
                  "etf":{"summary":{
                    "symbol":"VOO",
                    "name":"Vanguard S&P 500 ETF",
                    "fund_family":"Vanguard",
                    "currency":"USD",
                    "expense_ratio_net":0.0003,
                    "yield":0.0112,
                    "net_assets":500000000000
                  }},
                  "status":"ok"
                }
                """));

        ProviderModels.EtfProfile profile = provider.getProfile(instrument("VOO")).orElseThrow();

        assertEquals("Vanguard S&P 500 ETF", profile.name());
        assertEquals("NYSE Arca", profile.exchange());
        assertEquals("Vanguard", profile.issuer());
        assertEquals(0, profile.expenseRatio().compareTo(new BigDecimal("0.0003")));
        assertEquals(new BigDecimal("500000000000"), profile.aum());
        assertEquals(new BigDecimal("0.0112"), profile.dividendYield());
        assertEquals("11", query(server.requests().getFirst(), "dp"));
    }

    @Test
    void mapsExplicitAndRatioOnlySplits() {
        server.when("/splits", uri -> new Response(200, """
                {
                  "meta":{"symbol":"VOO"},
                  "splits":[
                    {"date":"2026-02-10","description":"4-for-1 split","from_factor":"4","to_factor":"1","ratio":0.25},
                    {"date":"2025-01-01","description":"outside range","ratio":0.5},
                    {"date":"2026-03-10","description":"2-for-1 split","ratio":0.5}
                  ],
                  "status":"ok"
                }
                """));

        List<ProviderModels.SplitEvent> splits = provider.getSplits(instrument("VOO"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertEquals(2, splits.size());
        assertEquals(new BigDecimal("4"), splits.getFirst().numerator());
        assertEquals(new BigDecimal("1"), splits.getFirst().denominator());
        assertEquals(new BigDecimal("1"), splits.getLast().numerator());
        assertEquals(new BigDecimal("0.5"), splits.getLast().denominator());
        assertEquals("2026-01-01", query(server.requests().getFirst(), "start_date"));
        assertEquals("2026-12-31", query(server.requests().getFirst(), "end_date"));
    }

    @Test
    void distinguishesSymbolNotFoundFromRetryableProviderFailures() {
        server.when("/symbol_search", uri -> new Response(200,
                "{\"status\":\"error\",\"code\":400,\"message\":\"Symbol DOES-NOT-EXIST not found\"}"));
        ProviderException notFound = assertThrows(ProviderException.class, () -> provider.search("DOES-NOT-EXIST"));
        assertFalse(notFound.retryable());

        server.when("/symbol_search", uri -> new Response(429,
                "{\"status\":\"error\",\"code\":429,\"message\":\"Rate limit exceeded\"}"));
        ProviderException rateLimited = assertThrows(ProviderException.class, () -> provider.search("VOO"));
        assertTrue(rateLimited.retryable());

        server.when("/symbol_search", uri -> new Response(503, "service unavailable"));
        ProviderException unavailable = assertThrows(ProviderException.class, () -> provider.search("VOO"));
        assertTrue(unavailable.retryable());
    }

    @Test
    void marksConnectionFailureRetryableAndDoesNotCallWhenUnconfigured() {
        TwelveDataProvider unreachable = new TwelveDataProvider(RestClient.builder(), new ObjectMapper(), "test-key",
                "http://127.0.0.1:0", 100, true);
        ProviderException connectionFailure = assertThrows(ProviderException.class, () -> unreachable.search("VOO"));
        assertTrue(connectionFailure.retryable());

        TwelveDataProvider unconfigured = new TwelveDataProvider(RestClient.builder(), new ObjectMapper(), " ",
                server.baseUrl().toString(), 1_000, true);
        ProviderException notConfigured = assertThrows(ProviderException.class, () -> unconfigured.search("VOO"));
        assertFalse(notConfigured.retryable());
        assertTrue(server.requests().isEmpty());
    }

    private static InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(symbol);
        instrument.setName(symbol + " ETF");
        instrument.setExchange("NYSE Arca");
        instrument.setCurrency("USD");
        instrument.setIssuer("Existing issuer");
        return instrument;
    }

    private static String query(URI uri, String key) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(key);
    }

    private record Response(int status, String body) { }

    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Function<URI, Response>> handlers = new HashMap<>();
        private final List<URI> requests = new CopyOnWriteArrayList<>();

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

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            requests.add(uri);
            Function<URI, Response> handler = handlers.get(uri.getPath());
            Response response = handler == null ? new Response(404, "not found") : handler.apply(uri);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
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
