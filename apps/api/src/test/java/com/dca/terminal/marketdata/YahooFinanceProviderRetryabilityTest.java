package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YahooFinanceProviderRetryabilityTest {
    private HttpServer server;
    private YahooFinanceProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v8/finance/chart/VOO", exchange -> {
            byte[] body = "rate limited".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        provider = new YahooFinanceProvider(RestClient.builder(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), 1_000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void intradayHttp429IsARetryableProviderError() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");

        ProviderException exception = assertThrows(ProviderException.class, () -> provider.getIntradayPrices(
                instrument, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31)));

        assertEquals(ProviderId.YAHOO, exception.provider());
        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("429"));
    }
}
