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

class YahooFinanceProviderExtendedQuoteTest {
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
    void usesFreshestOvernightPriceForCurrentQuote() {
        long regular = Instant.parse("2026-08-28T20:00:00Z").getEpochSecond();
        long pre = Instant.parse("2026-08-31T12:00:00Z").getEpochSecond();
        long post = Instant.parse("2026-08-29T00:00:00Z").getEpochSecond();
        long overnight = Instant.parse("2026-08-31T08:45:00Z").getEpochSecond();
        String body = """
                {
                  "quoteResponse": {"result": [{
                    "symbol": "VOO",
                    "regularMarketPrice": 700.00,
                    "regularMarketTime": %d,
                    "regularMarketPreviousClose": 698.00,
                    "preMarketPrice": 704.00,
                    "preMarketTime": %d,
                    "postMarketPrice": 702.00,
                    "postMarketTime": %d,
                    "overnightMarketPrice": 703.50,
                    "overnightMarketTime": %d,
                    "bid": 703.40,
                    "ask": 703.60
                  }], "error": null}
                }
                """.formatted(regular, pre, post, overnight);
        server.createContext("/v7/finance/quote", exchange -> respond(exchange, body));

        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        var quote = provider.getLatestQuote(instrument);

        // Pre-market is newer than the overnight tick in this fixture, so the latest timestamp wins.
        assertEquals(0, quote.price().compareTo(new java.math.BigDecimal("704.00")));
        assertEquals(Instant.ofEpochSecond(pre), quote.marketTimestamp());
        assertEquals(0, quote.previousClose().compareTo(new java.math.BigDecimal("698.00")));
        assertEquals("true", query(lastRequest.get(), "overnightPrice"));
    }

    @Test
    void usesOvernightWhenItIsTheNewestSessionQuote() {
        long regular = Instant.parse("2026-08-28T20:00:00Z").getEpochSecond();
        long post = Instant.parse("2026-08-29T00:00:00Z").getEpochSecond();
        long overnight = Instant.parse("2026-08-31T09:15:00Z").getEpochSecond();
        String body = """
                {
                  "quoteResponse": {"result": [{
                    "symbol": "VOO",
                    "regularMarketPrice": 700.00,
                    "regularMarketTime": %d,
                    "regularMarketPreviousClose": 698.00,
                    "postMarketPrice": 702.00,
                    "postMarketTime": %d,
                    "overnightMarketPrice": 705.25,
                    "overnightMarketTime": %d
                  }], "error": null}
                }
                """.formatted(regular, post, overnight);
        server.createContext("/v7/finance/quote", exchange -> respond(exchange, body));

        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        var quote = provider.getLatestQuote(instrument);

        assertEquals(0, quote.price().compareTo(new java.math.BigDecimal("705.25")));
        assertEquals(Instant.ofEpochSecond(overnight), quote.marketTimestamp());
    }

    @Test
    void doesNotLetAnOlderOvernightFieldOverrideANewerRegularQuote() {
        long overnight = Instant.parse("2026-08-31T09:00:00Z").getEpochSecond();
        long regular = Instant.parse("2026-08-31T20:00:00Z").getEpochSecond();
        String body = """
                {
                  "quoteResponse": {"result": [{
                    "symbol": "VOO",
                    "regularMarketPrice": 710.00,
                    "regularMarketTime": %d,
                    "regularMarketPreviousClose": 700.00,
                    "overnightMarketPrice": 705.00,
                    "overnightMarketTime": %d
                  }], "error": null}
                }
                """.formatted(regular, overnight);
        server.createContext("/v7/finance/quote", exchange -> respond(exchange, body));

        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        var quote = provider.getLatestQuote(instrument);

        assertEquals(0, quote.price().compareTo(new java.math.BigDecimal("710.00")));
        assertEquals(Instant.ofEpochSecond(regular), quote.marketTimestamp());
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
