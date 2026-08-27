package com.dca.terminal.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YahooFinanceProviderTest {
    private MockServer server;
    private YahooFinanceProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
        provider = new YahooFinanceProvider(RestClient.builder(), new ObjectMapper(),
                server.baseUrl().toString(), 1_000);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void searchesQqqAndVooFromYahooAutocompleteAndFiltersEtfs() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {
                  "ResultSet": {
                    "Query": "QQQ VOO",
                    "Result": [
                      {"symbol":"QQQ","name":"Invesco QQQ Trust","exch":"NGM","type":"E","exchDisp":"NASDAQ","typeDisp":"ETF"},
                      {"symbol":"VOO","name":"Vanguard S&P 500 ETF","exch":"PCX","type":"E","exchDisp":"NYSEArca","typeDisp":"ETF"},
                      {"symbol":"AAPL","name":"Apple Inc.","exch":"NMS","type":"S","exchDisp":"NASDAQ","typeDisp":"Equity"}
                    ]
                  }
                }
                """));

        List<ProviderModels.ProviderSearchResult> results = provider.search("QQQ");

        assertEquals(List.of("QQQ", "VOO"), results.stream()
                .map(ProviderModels.ProviderSearchResult::symbol).toList());
        assertEquals("Invesco QQQ Trust", results.getFirst().name());
        assertEquals("NASDAQ", results.getFirst().exchange());
        assertEquals("NYSEArca", results.get(1).exchange());
        assertEquals("QQQ", query(server.requests().getFirst(), "query"));
        assertEquals("en-US", query(server.requests().getFirst(), "lang"));
        assertEquals("US", query(server.requests().getFirst(), "region"));
        assertTrue(server.userAgents().getFirst().contains("Mozilla/5.0"));
    }

    @Test
    void returnsEmptyForAValidAutocompleteResponseWithoutEtfs() {
        server.when("/v6/finance/autocomplete", uri -> new Response(200, """
                {"ResultSet":{"Query":"DOES-NOT-EXIST","Result":[]}}
                """));

        assertTrue(provider.search("DOES-NOT-EXIST").isEmpty());
    }

    @Test
    void marksRateLimitAsRetryable() {
        server.when("/v6/finance/autocomplete", uri -> new Response(429, "Too Many Requests"));

        ProviderException exception = assertThrows(ProviderException.class, () -> provider.search("QQQ"));

        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("429"));
    }

    private static String query(URI uri, String key) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(key);
    }

    private record Response(int status, String body) { }

    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Function<URI, Response>> handlers = new HashMap<>();
        private final List<URI> requests = new CopyOnWriteArrayList<>();
        private final List<String> userAgents = new CopyOnWriteArrayList<>();

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

        private List<String> userAgents() {
            return userAgents;
        }

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            requests.add(uri);
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            Function<URI, Response> handler = handlers.get(uri.getPath());
            Response response = handler == null ? new Response(404, "not found") : handler.apply(uri);
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
