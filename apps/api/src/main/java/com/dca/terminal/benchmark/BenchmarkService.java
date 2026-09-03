package com.dca.terminal.benchmark;

import com.dca.terminal.benchmark.BenchmarkDtos.BenchmarkType;
import com.dca.terminal.benchmark.BenchmarkDtos.HistoryResponse;
import com.dca.terminal.benchmark.BenchmarkDtos.PricePoint;
import com.dca.terminal.benchmark.BenchmarkDtos.SearchResult;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.MarketCalendar;
import com.dca.terminal.marketdata.ProviderException;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.YahooFinanceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class BenchmarkService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.^=\\-]{1,24}");
    private static final String USER_AGENT = "Mozilla/5.0";

    private final YahooFinanceProvider yahoo;
    private final RestClient searchClient;
    private final Clock clock;

    public BenchmarkService(YahooFinanceProvider yahoo,
                            RestClient.Builder builder,
                            Clock clock,
                            @Value("${dca.market-data.yahoo.base-url}") String baseUrl,
                            @Value("${dca.market-data.yahoo.timeout-ms:5000}") int timeoutMs,
                            @Value("${dca.market-data.yahoo.proxy-url:}") String proxyUrl) {
        this.yahoo = yahoo;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        Proxy proxy = proxyFor(proxyUrl);
        if (proxy != null) factory.setProxy(proxy);
        this.searchClient = builder
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
    }

    public List<SearchResult> search(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank() || normalized.length() > 32) return List.of();
        try {
            JsonNode root = searchClient.get()
                    .uri(uri -> uri.path("/v6/finance/autocomplete")
                            .queryParam("query", normalized)
                            .queryParam("lang", "en-US")
                            .queryParam("region", "US")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null) return List.of();
            Map<String, SearchResult> bySymbol = new LinkedHashMap<>();
            for (JsonNode item : root.path("ResultSet").path("Result")) {
                String symbol = text(item, "symbol");
                BenchmarkType type = benchmarkType(text(item, "type", text(item, "typeDisp")));
                if (symbol.isBlank() || type == null || !SYMBOL.matcher(symbol).matches()) continue;
                String name = text(item, "name", symbol);
                String exchange = text(item, "exchDisp", text(item, "exch", null));
                bySymbol.putIfAbsent(symbol.toUpperCase(Locale.ROOT),
                        new SearchResult(symbol, name, exchange, type));
            }
            return bySymbol.values().stream().limit(20).toList();
        } catch (RestClientResponseException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BENCHMARK_SEARCH_UNAVAILABLE",
                    "Benchmark search is temporarily unavailable");
        } catch (RuntimeException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BENCHMARK_SEARCH_UNAVAILABLE",
                    "Benchmark search is temporarily unavailable");
        }
    }

    public HistoryResponse history(String rawSymbol, String rawName, BenchmarkType type, String range) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim();
        if (!SYMBOL.matcher(symbol).matches()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_BENCHMARK_SYMBOL", "Invalid benchmark symbol");
        }
        BenchmarkType resolvedType = type == null ? BenchmarkType.ETF : type;
        LocalDate end = LocalDate.now(clock.withZone(MARKET_ZONE));
        LocalDate start = rangeStart(end, range);
        InstrumentEntity synthetic = new InstrumentEntity();
        synthetic.setSymbol(symbol);
        synthetic.setName(rawName == null || rawName.isBlank() ? symbol : rawName.trim());
        synthetic.setCurrency("USD");
        List<PriceBar> bars;
        try {
            bars = yahoo.getHistoricalPrices(synthetic, start, end);
        } catch (ProviderException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BENCHMARK_HISTORY_UNAVAILABLE",
                    "Benchmark history is temporarily unavailable");
        }
        List<PricePoint> points = new ArrayList<>();
        for (PriceBar bar : bars == null ? List.<PriceBar>of() : bars) {
            if (bar == null || bar.tradeDate() == null) continue;
            BigDecimal value = positive(bar.adjustedClose()) ? bar.adjustedClose() : bar.close();
            if (!positive(value)) continue;
            points.add(new PricePoint(bar.tradeDate(), value));
        }
        points.sort(Comparator.comparing(PricePoint::date));
        LocalDate latest = points.isEmpty() ? null : points.get(points.size() - 1).date();
        FreshnessStatus status = latest == null ? FreshnessStatus.UNAVAILABLE
                : latest.isBefore(MarketCalendar.latestExpectedTradingDate(end)) ? FreshnessStatus.STALE : FreshnessStatus.FRESH;
        return new HistoryResponse(symbol, synthetic.getName(), resolvedType, "YAHOO", status, points);
    }

    private static LocalDate rangeStart(LocalDate end, String range) {
        if (range == null) return end.minusYears(5);
        return switch (range.toUpperCase(Locale.ROOT)) {
            case "1M" -> end.minusMonths(1);
            case "3M" -> end.minusMonths(3);
            case "YTD" -> LocalDate.of(end.getYear(), 1, 1);
            case "1Y" -> end.minusYears(1);
            case "5Y", "ALL" -> end.minusYears(5);
            default -> throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_BENCHMARK_RANGE",
                    "Unsupported benchmark range: " + range);
        };
    }

    private static BenchmarkType benchmarkType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("ETF") || normalized.contains("EXCHANGE TRADED FUND")) return BenchmarkType.ETF;
        if (normalized.equals("INDEX") || normalized.contains("INDEX")) return BenchmarkType.INDEX;
        return null;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static Proxy proxyFor(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) return null;
        try {
            URI uri = URI.create(proxyUrl.trim());
            if (uri.getHost() == null || uri.getPort() <= 0) return null;
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), uri.getPort()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
