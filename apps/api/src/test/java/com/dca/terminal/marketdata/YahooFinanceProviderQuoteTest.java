package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YahooFinanceProviderQuoteTest {
    private HttpServer server;
    private YahooFinanceProvider provider;

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
    void usesPriorTradingSessionInsteadOfChartWindowBaselineForDailyChange() {
        long aug26 = Instant.parse("2026-08-26T20:00:00Z").getEpochSecond();
        long aug27 = Instant.parse("2026-08-27T20:00:00Z").getEpochSecond();
        long aug28 = Instant.parse("2026-08-28T20:00:00Z").getEpochSecond();
        String body = """
                {
                  "chart": {"result": [{
                    "meta": {
                      "symbol": "VOO",
                      "exchangeTimezoneName": "America/New_York",
                      "regularMarketPrice": 707.24,
                      "regularMarketTime": %d,
                      "chartPreviousClose": 703.71
                    },
                    "timestamp": [%d, %d, %d],
                    "indicators": {"quote": [{
                      "close": [705.10, 708.75, 707.24]
                    }]}
                  }], "error": null}
                }
                """.formatted(aug28, aug26, aug27, aug28);
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange, body));

        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        var quote = provider.getLatestQuote(instrument);

        assertEquals(0, quote.price().compareTo(new java.math.BigDecimal("707.24")));
        assertEquals(0, quote.previousClose().compareTo(new java.math.BigDecimal("708.75")));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
