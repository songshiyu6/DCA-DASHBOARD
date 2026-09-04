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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YahooFinanceProviderDailyBoundaryTest {
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    @Test
    void usesNewYorkCalendarBoundariesAndExchangeDateForDailyHistory() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<URI> requestUri = new AtomicReference<>();
        server.createContext("/v8/finance/chart/QQQ", exchange -> handle(exchange, requestUri));
        server.start();
        try {
            YahooFinanceProvider provider = new YahooFinanceProvider(
                    RestClient.builder(), new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), 1_000);
            InstrumentEntity instrument = new InstrumentEntity();
            instrument.setSymbol("QQQ");
            LocalDate tradeDate = LocalDate.of(2026, 9, 3);

            List<ProviderModels.PriceBar> bars = provider.getHistoricalPrices(instrument, tradeDate, tradeDate);

            assertEquals(1, bars.size());
            assertEquals(tradeDate, bars.getFirst().tradeDate());
            assertEquals(Long.toString(tradeDate.atStartOfDay(MARKET_ZONE).toEpochSecond()),
                    query(requestUri.get(), "period1"));
            assertEquals(Long.toString(tradeDate.plusDays(1).atStartOfDay(MARKET_ZONE).toEpochSecond()),
                    query(requestUri.get(), "period2"));
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicReference<URI> requestUri) throws IOException {
        requestUri.set(exchange.getRequestURI());
        long timestamp = Instant.parse("2026-09-04T01:00:00Z").getEpochSecond();
        String body = """
                {"chart":{"result":[{
                  "meta":{"exchangeTimezoneName":"America/New_York"},
                  "timestamp":[%d],
                  "indicators":{"quote":[{"close":[611.25]}],"adjclose":[{"adjclose":[611.25]}]}
                }],"error":null}}
                """.formatted(timestamp);
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
