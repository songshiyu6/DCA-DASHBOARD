package com.dca.terminal.benchmark;

import com.dca.terminal.benchmark.BenchmarkDtos.BenchmarkType;
import com.dca.terminal.benchmark.BenchmarkDtos.HistoryResponse;
import com.dca.terminal.benchmark.BenchmarkDtos.SearchResult;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.marketdata.YahooFinanceProvider;
import com.dca.terminal.observability.ObservabilityMetrics;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkServiceTest {
    private MockServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void parsesYahooEtfIndexAndEquityTypesWithoutDroppingValidResults() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {
                  "ResultSet": {
                    "Result": [
                      {"symbol":"QQQ","name":"Invesco QQQ Trust","exch":"NGM","type":"E","exchDisp":"NASDAQ","typeDisp":"ETF"},
                      {"symbol":"^GSPC","name":"S&P 500","exch":"SNP","type":"I","exchDisp":"SNP","typeDisp":"Index"},
                      {"symbol":"AAPL","name":"Apple Inc.","exch":"NMS","type":"S","exchDisp":"NASDAQ","typeDisp":"Equity"},
                      {"symbol":"TSLA","name":"Tesla, Inc.","exch":"NMS","type":"S","exchDisp":"NASDAQ"},
                      {"symbol":"AAPL260918C00200000","name":"AAPL option","type":"O","typeDisp":"Option"}
                    ]
                  }
                }
                """));

        List<SearchResult> results = service(1).search("AAPL");

        assertEquals(List.of("QQQ", "^GSPC", "AAPL", "TSLA"), results.stream().map(SearchResult::symbol).toList());
        assertEquals(List.of(BenchmarkType.ETF, BenchmarkType.INDEX, BenchmarkType.EQUITY, BenchmarkType.EQUITY),
                results.stream().map(SearchResult::type).toList());
        assertEquals(1, server.requests().size());
    }

    @Test
    void usesChinaRegionForAshareSymbolSearch() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {"ResultSet":{"Result":[]}}
                """));

        service(1).search("510300.SS");

        assertEquals("CN", query(server.requests().getFirst(), "region"));
    }

    @Test
    void returnsEmptyForAValidYahooResponseWithNoSupportedResults() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {"ResultSet":{"Result":[{"symbol":"ES=F","name":"E-mini S&P 500","type":"F","typeDisp":"Future"}]}}
                """));

        assertTrue(service(1).search("ES").isEmpty());
        assertEquals(1, server.requests().size());
    }

    @Test
    void treatsMalformedSuccessfulYahooResponseAsUnavailableInsteadOfEmpty() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {"finance":{"result":[]}}
                """));

        DomainException exception = assertThrows(DomainException.class, () -> service(1).search("QQQ"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        assertEquals("BENCHMARK_SEARCH_UNAVAILABLE", exception.code());
        assertEquals(1, server.requests().size());
    }

    @Test
    void retriesYahooRateLimitThenReturnsServiceUnavailableInsteadOfEmptyResults() {
        server.when("/v6/finance/autocomplete", uri -> new Response(429, "Too Many Requests"));

        DomainException exception = assertThrows(DomainException.class, () -> service(2).search("QQQ"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        assertEquals("BENCHMARK_SEARCH_UNAVAILABLE", exception.code());
        assertEquals(2, server.requests().size());
    }

    @Test
    void excludesCurrentTradingDayDailyBarBeforeRegularClose() {
        server.when("/v8/finance/chart/QQQ", uri -> new Response(200,
                chartResponse("America/New_York", "2026-09-02T13:30:00Z", "2026-09-03T13:30:00Z")));
        Clock beforeClose = Clock.fixed(Instant.parse("2026-09-03T19:30:00Z"), ZoneOffset.UTC);

        HistoryResponse history = service(1, beforeClose).history("QQQ", "Invesco QQQ Trust", BenchmarkType.ETF, "1M");

        assertEquals(List.of("2026-09-02"), history.points().stream().map(point -> point.date().toString()).toList());
        assertEquals(FreshnessStatus.FRESH, history.dataStatus());
    }

    @Test
    void marksBenchmarkStaleAfterCloseUntilCurrentTradingDayBarArrives() {
        server.when("/v8/finance/chart/QQQ", uri -> new Response(200,
                chartResponse("America/New_York", "2026-09-02T13:30:00Z")));
        Clock afterClose = Clock.fixed(Instant.parse("2026-09-03T20:05:00Z"), ZoneOffset.UTC);

        HistoryResponse history = service(1, afterClose).history("QQQ", "Invesco QQQ Trust", BenchmarkType.ETF, "1M");

        assertEquals(List.of("2026-09-02"), history.points().stream().map(point -> point.date().toString()).toList());
        assertEquals(FreshnessStatus.STALE, history.dataStatus());
    }

    @Test
    void includesCurrentTradingDayBarAfterRegularClose() {
        server.when("/v8/finance/chart/QQQ", uri -> new Response(200,
                chartResponse("America/New_York", "2026-09-02T13:30:00Z", "2026-09-03T13:30:00Z")));
        Clock afterClose = Clock.fixed(Instant.parse("2026-09-03T20:05:00Z"), ZoneOffset.UTC);

        HistoryResponse history = service(1, afterClose).history("QQQ", "Invesco QQQ Trust", BenchmarkType.ETF, "1M");

        assertEquals(List.of("2026-09-02", "2026-09-03"),
                history.points().stream().map(point -> point.date().toString()).toList());
        assertEquals(FreshnessStatus.FRESH, history.dataStatus());
    }

    @Test
    void chinaBenchmarkUsesShanghaiBoundaryAndPreviousCloseBefore1500() {
        server.when("/v8/finance/chart/510300.SS", uri -> new Response(200,
                chartResponse("Asia/Shanghai", "2026-09-03T01:30:00Z", "2026-09-04T01:30:00Z")));
        Clock beforeChinaClose = Clock.fixed(Instant.parse("2026-09-04T04:08:00Z"), ZoneOffset.UTC);

        HistoryResponse history = service(1, beforeChinaClose)
                .history("510300.SS", "CSI 300 ETF", BenchmarkType.ETF, "1M");

        assertEquals(List.of("2026-09-03"), history.points().stream().map(point -> point.date().toString()).toList());
        assertEquals(FreshnessStatus.FRESH, history.dataStatus());
        URI request = server.requests().getFirst();
        assertEquals(Long.toString(LocalDate.of(2026, 9, 5)
                        .atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond()),
                query(request, "period2"));
    }

    @Test
    void chinaBenchmarkAcceptsCurrentCloseAfter1500Shanghai() {
        server.when("/v8/finance/chart/510300.SS", uri -> new Response(200,
                chartResponse("Asia/Shanghai", "2026-09-03T01:30:00Z", "2026-09-04T01:30:00Z")));
        Clock afterChinaClose = Clock.fixed(Instant.parse("2026-09-04T07:05:00Z"), ZoneOffset.UTC);

        HistoryResponse history = service(1, afterChinaClose)
                .history("510300.SS", "CSI 300 ETF", BenchmarkType.ETF, "1M");

        assertEquals(List.of("2026-09-03", "2026-09-04"),
                history.points().stream().map(point -> point.date().toString()).toList());
        assertEquals(FreshnessStatus.FRESH, history.dataStatus());
    }

    @Test
    void chinaHolidayDoesNotCreateFalseMissingClose() {
        server.when("/v8/finance/chart/510300.SS", uri -> new Response(200,
                chartResponse("Asia/Shanghai", "2026-09-24T01:30:00Z")));
        Clock midAutumnHoliday = Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);

        HistoryResponse history = service(1, midAutumnHoliday)
                .history("510300.SS", "CSI 300 ETF", BenchmarkType.ETF, "1M");

        assertEquals(List.of("2026-09-24"), history.points().stream().map(point -> point.date().toString()).toList());
        assertEquals(FreshnessStatus.FRESH, history.dataStatus());
    }

    private BenchmarkService service(int attempts) {
        return service(attempts, Clock.systemUTC());
    }

    private BenchmarkService service(int attempts, Clock clock) {
        RestClient.Builder builder = RestClient.builder();
        YahooFinanceProvider yahoo = new YahooFinanceProvider(builder, new ObjectMapper(), server.baseUrl().toString(), 1_000);
        return new BenchmarkService(yahoo, builder, clock, server.baseUrl().toString(),
                1_000, "", attempts, ObservabilityMetrics.noop());
    }

    private static String chartResponse(String exchangeTimezoneName, String... timestamps) {
        String epochs = java.util.Arrays.stream(timestamps)
                .map(value -> Long.toString(Instant.parse(value).getEpochSecond()))
                .collect(java.util.stream.Collectors.joining(","));
        String closes = java.util.stream.IntStream.range(0, timestamps.length)
                .mapToObj(index -> Integer.toString(100 + index))
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"chart":{"result":[{
                  "meta":{"exchangeTimezoneName":"%s"},
                  "timestamp":[%s],
                  "indicators":{"quote":[{"close":[%s]}],"adjclose":[{"adjclose":[%s]}]}
                }],"error":null}}
                """.formatted(exchangeTimezoneName, epochs, closes, closes);
    }

    private static String query(URI uri, String key) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(key);
    }

    private record Response(int status, String body) { }

    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;
        private final List<URI> requests = new CopyOnWriteArrayList<>();
        private Function<URI, Response> handler = uri -> new Response(404, "not found");
        private String path = "/";

        private MockServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void when(String path, Function<URI, Response> handler) {
            this.path = path;
            this.handler = handler;
        }

        private URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private List<URI> requests() {
            return new ArrayList<>(requests);
        }

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            requests.add(uri);
            Response response = path.equals(uri.getPath()) ? handler.apply(uri) : new Response(404, "not found");
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
