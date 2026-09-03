package com.dca.terminal.benchmark;

import com.dca.terminal.benchmark.BenchmarkDtos.BenchmarkType;
import com.dca.terminal.benchmark.BenchmarkDtos.SearchResult;
import com.dca.terminal.common.DomainException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

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
    void returnsEmptyForAValidYahooResponseWithNoSupportedResults() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {"ResultSet":{"Result":[{"symbol":"ES=F","name":"E-mini S&P 500","type":"F","typeDisp":"Future"}]}}
                """));

        assertTrue(service(1).search("ES").isEmpty());
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

    private BenchmarkService service(int attempts) {
        RestClient.Builder builder = RestClient.builder();
        YahooFinanceProvider yahoo = new YahooFinanceProvider(builder, new ObjectMapper(), server.baseUrl().toString(), 1_000);
        return new BenchmarkService(yahoo, builder, Clock.systemUTC(), server.baseUrl().toString(),
                1_000, "", attempts, ObservabilityMetrics.noop());
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
