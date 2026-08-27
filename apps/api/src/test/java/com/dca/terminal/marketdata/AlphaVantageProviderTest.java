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
import java.time.LocalDate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlphaVantageProviderTest {
    private MockServer server;
    private AlphaVantageProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
        provider = new AlphaVantageProvider(RestClient.builder(), new ObjectMapper(), "test-key",
                server.baseUrl().toString(), 1_000, true);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void readsOnlyEtfProfileMetricsAndKeepsInstrumentIdentity() {
        server.when("/query", uri -> new Response(200, """
                {
                  "net_assets":"452800000000",
                  "net_expense_ratio":"0.0018",
                  "dividend_yield":"0.0042",
                  "inception_date":"1999-03-10",
                  "holdings":[]
                }
                """));

        ProviderModels.EtfProfile profile = provider.getProfile(instrument("QQQ")).orElseThrow();

        assertEquals("Invesco QQQ Trust", profile.name());
        assertEquals("NASDAQ", profile.exchange());
        assertEquals("Invesco", profile.issuer());
        assertEquals("USD", profile.currency());
        assertEquals(new BigDecimal("452800000000"), profile.aum());
        assertEquals(new BigDecimal("0.0018"), profile.expenseRatio());
        assertEquals(new BigDecimal("0.0042"), profile.dividendYield());
        URI request = server.requests().getFirst();
        assertEquals("ETF_PROFILE", query(request, "function"));
        assertEquals("QQQ", query(request, "symbol"));
        assertEquals("test-key", query(request, "apikey"));
    }

    @Test
    void classifiesAlphaRateLimitAsRetryableAndInvalidSymbolAsPermanent() {
        server.when("/query", uri -> new Response(200,
                "{\"Error Message\":\"Invalid API call. The symbol does not exist.\"}"));
        ProviderException invalidSymbol = assertThrows(ProviderException.class,
                () -> provider.getProfile(instrument("NOPE")));
        assertFalse(invalidSymbol.retryable());

        server.when("/query", uri -> new Response(200,
                "{\"Note\":\"Thank you for using Alpha Vantage! Our standard API call frequency is 5 calls per minute.\"}"));
        ProviderException rateLimited = assertThrows(ProviderException.class,
                () -> provider.getProfile(instrument("QQQ")));
        assertTrue(rateLimited.retryable());

        server.when("/query", uri -> new Response(503, "service unavailable"));
        ProviderException unavailable = assertThrows(ProviderException.class,
                () -> provider.getProfile(instrument("QQQ")));
        assertTrue(unavailable.retryable());
    }

    @Test
    void explicitlyRejectsCapabilitiesOutsideV1ProfileScope() {
        InstrumentEntity instrument = instrument("QQQ");
        ProviderException search = assertThrows(ProviderException.class, () -> provider.search("QQQ"));
        ProviderException quote = assertThrows(ProviderException.class, () -> provider.getLatestQuote(instrument));
        ProviderException history = assertThrows(ProviderException.class,
                () -> provider.getHistoricalPrices(instrument, LocalDate.now().minusDays(1), LocalDate.now()));
        ProviderException intraday = assertThrows(ProviderException.class,
                () -> provider.getIntradayPrices(instrument, LocalDate.now(), LocalDate.now()));
        ProviderException splits = assertThrows(ProviderException.class,
                () -> provider.getSplits(instrument, LocalDate.now().minusDays(1), LocalDate.now()));

        assertFalse(search.retryable());
        assertFalse(quote.retryable());
        assertFalse(history.retryable());
        assertFalse(intraday.retryable());
        assertFalse(splits.retryable());
        assertTrue(search.getMessage().contains("does not support symbol search"));
        assertTrue(splits.getMessage().contains("does not support split events"));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void unconfiguredProviderDoesNotMakeNetworkRequest() {
        AlphaVantageProvider unconfigured = new AlphaVantageProvider(RestClient.builder(), new ObjectMapper(), "",
                server.baseUrl().toString(), 1_000, true);

        ProviderException exception = assertThrows(ProviderException.class,
                () -> unconfigured.getProfile(instrument("QQQ")));

        assertFalse(exception.retryable());
        assertTrue(exception.getMessage().contains("not configured"));
        assertTrue(server.requests().isEmpty());
    }

    private static InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(symbol);
        instrument.setName("Invesco QQQ Trust");
        instrument.setExchange("NASDAQ");
        instrument.setCurrency("USD");
        instrument.setIssuer("Invesco");
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
