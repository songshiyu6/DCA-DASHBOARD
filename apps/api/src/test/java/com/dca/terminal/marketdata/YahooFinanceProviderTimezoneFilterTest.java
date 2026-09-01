package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
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
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YahooFinanceProviderTimezoneFilterTest {
    private HttpServer server;

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
    void rawTimestampFilteredByProviderTimezoneIsObservableAndRetryableAfterRegularOpen() {
        long postStart = Instant.parse("2026-01-05T21:00:00Z").getEpochSecond();
        long postEnd = Instant.parse("2026-01-06T01:00:00Z").getEpochSecond();
        long latePost = Instant.parse("2026-01-06T00:30:00Z").getEpochSecond();
        server.createContext("/v8/finance/chart/VOO", exchange -> respond(exchange,
                chart(postStart, postEnd, latePost)));
        YahooFinanceProvider provider = new YahooFinanceProvider(
                RestClient.builder(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), 1_000, "", false,
                "https://fc.yahoo.com",
                Clock.fixed(Instant.parse("2026-01-05T22:00:00Z"), ZoneOffset.UTC));

        ProviderModels.IntradayResult diagnostics = provider.getIntradayResult(instrument(),
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));
        ProviderException exception = assertThrows(ProviderException.class, () -> provider.getIntradayPrices(
                instrument(), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)));

        assertEquals(1, diagnostics.rawTimestampCount());
        assertEquals(0, diagnostics.dateMatchedCount());
        assertEquals(0, diagnostics.tradingPeriodMatchedCount());
        assertEquals(0, diagnostics.nonNullCloseCount());
        assertTrue(diagnostics.bars().isEmpty());
        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("requested New York trading date"));
    }

    private String chart(long postStart, long postEnd, long timestamp) {
        return "{\"chart\":{\"result\":[{\"meta\":{\"exchangeTimezoneName\":\"UTC\",\"currentTradingPeriod\":{\"post\":{\"start\":"
                + postStart + ",\"end\":" + postEnd + "}}},\"timestamp\":[" + timestamp
                + "],\"indicators\":{\"quote\":[{\"close\":[100.25]}]}}],\"error\":null}}";
    }

    private InstrumentEntity instrument() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
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
