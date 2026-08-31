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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YahooFinanceProviderAuthenticationTest {
    private HttpServer server;
    private YahooFinanceProvider provider;
    private final AtomicReference<String> crumbAccept = new AtomicReference<>();
    private final AtomicReference<String> crumbCookie = new AtomicReference<>();
    private final AtomicReference<String> quoteCookie = new AtomicReference<>();
    private final AtomicReference<URI> quoteUri = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cookie", this::cookie);
        server.createContext("/v1/test/getcrumb", this::crumb);
        server.createContext("/v7/finance/quote", this::quote);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new YahooFinanceProvider(
                RestClient.builder(),
                new ObjectMapper(),
                baseUrl,
                1_000,
                "",
                true,
                baseUrl + "/cookie");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void requestsPlainTextCrumbAndUsesItForAuthenticatedOvernightQuote() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");

        var result = provider.getLatestQuote(instrument);

        assertTrue(crumbAccept.get().contains("text/plain"));
        assertEquals("A1=test-cookie", crumbCookie.get());
        assertEquals("A1=test-cookie", quoteCookie.get());
        assertEquals("crumb-token", query(quoteUri.get(), "crumb"));
        assertEquals("true", query(quoteUri.get(), "overnightPrice"));
        assertEquals(0, result.price().compareTo(new java.math.BigDecimal("705.00")));
        assertEquals(Instant.parse("2026-08-31T04:54:28Z"), result.marketTimestamp());
        assertEquals(QuoteSession.OVERNIGHT, result.session());
    }

    private void cookie(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", "A1=test-cookie; Path=/; HttpOnly");
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private void crumb(HttpExchange exchange) throws IOException {
        crumbAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
        crumbCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
        respond(exchange, "crumb-token", "text/plain");
    }

    private void quote(HttpExchange exchange) throws IOException {
        quoteCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
        quoteUri.set(exchange.getRequestURI());
        long regular = Instant.parse("2026-08-28T20:00:00Z").getEpochSecond();
        long overnight = Instant.parse("2026-08-31T04:54:28Z").getEpochSecond();
        String body = """
                {
                  "quoteResponse": {"result": [{
                    "symbol": "VOO",
                    "regularMarketPrice": 707.24,
                    "regularMarketTime": %d,
                    "regularMarketPreviousClose": 706.10,
                    "overnightMarketPrice": 705.00,
                    "overnightMarketTime": %d
                  }], "error": null}
                }
                """.formatted(regular, overnight);
        respond(exchange, body, "application/json");
    }

    private static void respond(HttpExchange exchange, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String query(URI uri, String key) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(key);
    }
}
