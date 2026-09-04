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
import com.dca.terminal.observability.ObservabilityMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class BenchmarkService {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);
    private static final ZoneId US_MARKET_ZONE = ZoneId.of("America/New_York");
    private static final ZoneId CHINA_MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime US_REGULAR_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime CHINA_REGULAR_CLOSE = LocalTime.of(15, 0);
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.^=\\-]{1,24}");
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final String SEARCH_OPERATION = "benchmark_search";
    private static final String HISTORY_OPERATION = "benchmark_history";

    private final YahooFinanceProvider yahoo;
    private final RestClient searchClient;
    private final Clock clock;
    private final int providerAttempts;
    private final MeterRegistry meterRegistry;

    public BenchmarkService(YahooFinanceProvider yahoo,
                            RestClient.Builder builder,
                            Clock clock,
                            @Value("${dca.market-data.yahoo.base-url}") String baseUrl,
                            @Value("${dca.market-data.yahoo.timeout-ms:5000}") int timeoutMs,
                            @Value("${dca.market-data.yahoo.proxy-url:}") String proxyUrl,
                            @Value("${dca.market-data.provider-attempts:2}") int providerAttempts,
                            MeterRegistry meterRegistry) {
        this.yahoo = yahoo;
        this.clock = clock;
        this.providerAttempts = Math.max(1, providerAttempts);
        this.meterRegistry = meterRegistry == null ? ObservabilityMetrics.noop() : meterRegistry;
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

        for (int attempt = 1; attempt <= providerAttempts; attempt++) {
            Timer.Sample sample = ObservabilityMetrics.start(meterRegistry);
            String outcome = "error";
            try {
                JsonNode root = searchClient.get()
                        .uri(uri -> uri.path("/v6/finance/autocomplete")
                                .queryParam("query", normalized)
                                .queryParam("lang", "en-US")
                                .queryParam("region", searchRegion(normalized))
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                SearchParseResult parsed = parseSearch(root);
                outcome = parsed.results().isEmpty() ? "empty" : "success";
                log.info("operation={} provider=YAHOO queryLength={} httpStatus=200 rawResultCount={} acceptedResultCount={} droppedUnknownTypeCount={} outcome={}",
                        SEARCH_OPERATION, normalized.length(), parsed.rawResultCount(), parsed.results().size(),
                        parsed.droppedUnknownTypeCount(), outcome);
                return parsed.results();
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                log.warn("operation={} provider=YAHOO queryLength={} httpStatus={} attempt={} maxAttempts={} outcome=error reason={}",
                        SEARCH_OPERATION, normalized.length(), status, attempt, providerAttempts,
                        exception.getStatusText());
                if (!retryable || attempt >= providerAttempts || !pauseAfterAttempt(attempt)) {
                    throw searchUnavailable();
                }
            } catch (RuntimeException exception) {
                log.warn("operation={} provider=YAHOO queryLength={} httpStatus=0 attempt={} maxAttempts={} outcome=error reason={}",
                        SEARCH_OPERATION, normalized.length(), attempt, providerAttempts,
                        exception.getClass().getSimpleName());
                if (attempt >= providerAttempts || !pauseAfterAttempt(attempt)) {
                    throw searchUnavailable();
                }
            } finally {
                ObservabilityMetrics.stop(meterRegistry, sample, ObservabilityMetrics.PROVIDER_REQUEST,
                        "provider", "YAHOO", "operation", SEARCH_OPERATION, "outcome", outcome);
            }
        }
        throw searchUnavailable();
    }

    public HistoryResponse history(String rawSymbol, String rawName, BenchmarkType type, String range) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim();
        if (!SYMBOL.matcher(symbol).matches()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_BENCHMARK_SYMBOL", "Invalid benchmark symbol");
        }
        BenchmarkType resolvedType = type == null ? BenchmarkType.ETF : type;
        MarketProfile market = marketProfile(symbol);
        ZonedDateTime now = clock.instant().atZone(market.zone());
        LocalDate end = now.toLocalDate();
        LocalDate expectedCloseDate = latestCompletedRegularCloseDate(now, market);
        LocalDate start = rangeStart(end, range);
        String name = rawName == null || rawName.isBlank() ? symbol : rawName.trim();
        List<PriceBar> bars;
        if (market.chinaAshare()) {
            bars = chinaBenchmarkHistory(symbol, start, end, market);
        } else {
            InstrumentEntity synthetic = new InstrumentEntity();
            synthetic.setSymbol(symbol);
            synthetic.setName(name);
            synthetic.setCurrency("USD");
            try {
                bars = yahoo.getHistoricalPrices(synthetic, start, end);
            } catch (ProviderException exception) {
                throw historyUnavailable();
            }
        }
        List<PricePoint> points = new ArrayList<>();
        for (PriceBar bar : bars == null ? List.<PriceBar>of() : bars) {
            if (bar == null || bar.tradeDate() == null || bar.tradeDate().isAfter(expectedCloseDate)) continue;
            BigDecimal value = positive(bar.adjustedClose()) ? bar.adjustedClose() : bar.close();
            if (!positive(value)) continue;
            points.add(new PricePoint(bar.tradeDate(), value));
        }
        points.sort(Comparator.comparing(PricePoint::date));
        LocalDate latest = points.isEmpty() ? null : points.get(points.size() - 1).date();
        FreshnessStatus status = latest == null ? FreshnessStatus.UNAVAILABLE
                : latest.isBefore(expectedCloseDate) ? FreshnessStatus.STALE : FreshnessStatus.FRESH;
        return new HistoryResponse(symbol, name, resolvedType, "YAHOO", status, points);
    }

    private List<PriceBar> chinaBenchmarkHistory(String symbol, LocalDate start, LocalDate end, MarketProfile market) {
        for (int attempt = 1; attempt <= providerAttempts; attempt++) {
            Timer.Sample sample = ObservabilityMetrics.start(meterRegistry);
            String outcome = "error";
            try {
                JsonNode root = searchClient.get()
                        .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                                .queryParam("interval", "1d")
                                .queryParam("events", "div,splits")
                                .queryParam("period1", start.atStartOfDay(market.zone()).toEpochSecond())
                                .queryParam("period2", end.plusDays(1).atStartOfDay(market.zone()).toEpochSecond())
                                .build(symbol))
                        .retrieve()
                        .body(JsonNode.class);
                List<PriceBar> bars = parseDailyHistory(root, start, end, market.zone());
                outcome = bars.isEmpty() ? "empty" : "success";
                log.info("operation={} provider=YAHOO market=CN symbol={} from={} to={} acceptedBars={} outcome={}",
                        HISTORY_OPERATION, symbol, start, end, bars.size(), outcome);
                return bars;
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                boolean retryable = status == 408 || status == 429 || status >= 500;
                log.warn("operation={} provider=YAHOO market=CN symbol={} httpStatus={} attempt={} maxAttempts={} outcome=error",
                        HISTORY_OPERATION, symbol, status, attempt, providerAttempts);
                if (!retryable || attempt >= providerAttempts || !pauseAfterAttempt(attempt)) {
                    throw historyUnavailable();
                }
            } catch (RuntimeException exception) {
                log.warn("operation={} provider=YAHOO market=CN symbol={} httpStatus=0 attempt={} maxAttempts={} outcome=error reason={}",
                        HISTORY_OPERATION, symbol, attempt, providerAttempts, exception.getClass().getSimpleName());
                if (attempt >= providerAttempts || !pauseAfterAttempt(attempt)) {
                    throw historyUnavailable();
                }
            } finally {
                ObservabilityMetrics.stop(meterRegistry, sample, ObservabilityMetrics.PROVIDER_REQUEST,
                        "provider", "YAHOO", "operation", HISTORY_OPERATION, "outcome", outcome);
            }
        }
        throw historyUnavailable();
    }

    private static List<PriceBar> parseDailyHistory(JsonNode root, LocalDate start, LocalDate end, ZoneId fallbackZone) {
        JsonNode results = root == null ? null : root.path("chart").path("result");
        if (results == null || !results.isArray() || results.isEmpty() || !results.get(0).isObject()) {
            throw new IllegalStateException("Yahoo benchmark history response is malformed");
        }
        JsonNode chart = results.get(0);
        JsonNode timestamps = chart.path("timestamp");
        JsonNode quote = chart.path("indicators").path("quote").path(0);
        JsonNode adjusted = chart.path("indicators").path("adjclose").path(0).path("adjclose");
        if (!timestamps.isArray() || !quote.isObject()) {
            throw new IllegalStateException("Yahoo benchmark history has no daily series");
        }
        ZoneId exchangeZone = exchangeZone(chart.path("meta"), fallbackZone);
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode timestampNode = timestamps.get(i);
            if (timestampNode == null || !timestampNode.isNumber()) continue;
            LocalDate date = Instant.ofEpochSecond(timestampNode.asLong()).atZone(exchangeZone).toLocalDate();
            if (date.isBefore(start) || date.isAfter(end)) continue;
            BigDecimal close = decimalAt(quote.path("close"), i);
            if (!positive(close)) continue;
            bars.add(new PriceBar(date, decimalAt(quote.path("open"), i), decimalAt(quote.path("high"), i),
                    decimalAt(quote.path("low"), i), close, decimalAt(adjusted, i), longAt(quote.path("volume"), i)));
        }
        return bars;
    }

    private static LocalDate latestCompletedRegularCloseDate(ZonedDateTime now, MarketProfile market) {
        LocalDate today = now.toLocalDate();
        LocalDate latestTradingDate = latestExpectedTradingDate(today, market);
        if (today.equals(latestTradingDate) && now.toLocalTime().isBefore(market.regularClose())) {
            return latestExpectedTradingDate(today.minusDays(1), market);
        }
        return latestTradingDate;
    }

    private static LocalDate latestExpectedTradingDate(LocalDate date, MarketProfile market) {
        if (!market.chinaAshare()) return MarketCalendar.latestExpectedTradingDate(date);
        LocalDate candidate = date;
        while (!isChinaTradingDay(candidate)) candidate = candidate.minusDays(1);
        return candidate;
    }

    private static boolean isChinaTradingDay(LocalDate date) {
        if (date == null || date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !isChinaExchangeHoliday(date);
    }

    private static boolean isChinaExchangeHoliday(LocalDate date) {
        if (date.getYear() != 2026) return false;
        return between(date, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))
                || between(date, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 23))
                || date.equals(LocalDate.of(2026, 4, 6))
                || between(date, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5))
                || date.equals(LocalDate.of(2026, 6, 19))
                || date.equals(LocalDate.of(2026, 9, 25))
                || between(date, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));
    }

    private static boolean between(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private static MarketProfile marketProfile(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.endsWith(".SS") || normalized.endsWith(".SZ")) {
            return new MarketProfile(CHINA_MARKET_ZONE, CHINA_REGULAR_CLOSE, true);
        }
        return new MarketProfile(US_MARKET_ZONE, US_REGULAR_CLOSE, false);
    }

    private static String searchRegion(String query) {
        if (query == null) return "US";
        String normalized = query.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("\\d{6}(?:\\.(?:SS|SZ))?") || normalized.matches(".*[\\u4e00-\\u9fff].*")) {
            return "CN";
        }
        return "US";
    }

    private static SearchParseResult parseSearch(JsonNode root) {
        if (root == null) throw new IllegalStateException("Yahoo benchmark search returned an empty body");
        JsonNode rawResults = root.path("ResultSet").path("Result");
        if (!rawResults.isArray()) throw new IllegalStateException("Yahoo benchmark search response is malformed");
        int rawResultCount = rawResults.size();
        int droppedUnknownTypeCount = 0;
        Map<String, SearchResult> bySymbol = new LinkedHashMap<>();
        for (JsonNode item : rawResults) {
            String symbol = text(item, "symbol");
            BenchmarkType type = benchmarkType(item);
            if (type == null) {
                droppedUnknownTypeCount++;
                continue;
            }
            if (symbol.isBlank() || !SYMBOL.matcher(symbol).matches()) continue;
            String name = text(item, "name", symbol);
            String exchange = text(item, "exchDisp", text(item, "exch", null));
            bySymbol.putIfAbsent(symbol.toUpperCase(Locale.ROOT),
                    new SearchResult(symbol, name, exchange, type));
        }
        return new SearchParseResult(bySymbol.values().stream().limit(20).toList(),
                rawResultCount, droppedUnknownTypeCount);
    }

    private static BenchmarkType benchmarkType(JsonNode item) {
        BenchmarkType displayType = benchmarkType(text(item, "typeDisp", null));
        return displayType != null ? displayType : benchmarkType(text(item, "type", null));
    }

    private static BenchmarkType benchmarkType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("E") || normalized.equals("ETF") || normalized.contains("EXCHANGE TRADED FUND")) {
            return BenchmarkType.ETF;
        }
        if (normalized.equals("I") || normalized.equals("INDEX") || normalized.contains("INDEX")) {
            return BenchmarkType.INDEX;
        }
        if (normalized.equals("S") || normalized.equals("EQUITY") || normalized.equals("STOCK")
                || normalized.contains("EQUITY")) {
            return BenchmarkType.EQUITY;
        }
        return null;
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

    private static DomainException searchUnavailable() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BENCHMARK_SEARCH_UNAVAILABLE",
                "Benchmark search is temporarily unavailable");
    }

    private static DomainException historyUnavailable() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BENCHMARK_HISTORY_UNAVAILABLE",
                "Benchmark history is temporarily unavailable");
    }

    private static boolean pauseAfterAttempt(int attempt) {
        try {
            Thread.sleep(Math.min(250L * attempt, 1_000L));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal decimalAt(JsonNode array, int index) {
        if (!array.isArray() || index >= array.size() || array.get(index).isNull()) return null;
        return array.get(index).isNumber() ? array.get(index).decimalValue() : null;
    }

    private static Long longAt(JsonNode array, int index) {
        if (!array.isArray() || index >= array.size() || array.get(index).isNull()) return null;
        return array.get(index).isNumber() ? array.get(index).longValue() : null;
    }

    private static ZoneId exchangeZone(JsonNode meta, ZoneId fallback) {
        String name = text(meta, "exchangeTimezoneName", fallback.getId());
        try {
            return ZoneId.of(name);
        } catch (RuntimeException ignored) {
            return fallback;
        }
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

    private record SearchParseResult(List<SearchResult> results, int rawResultCount, int droppedUnknownTypeCount) { }
    private record MarketProfile(ZoneId zone, LocalTime regularClose, boolean chinaAshare) { }
}
