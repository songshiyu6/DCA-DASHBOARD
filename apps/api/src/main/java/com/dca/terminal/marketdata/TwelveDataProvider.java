package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentType;
import com.dca.terminal.marketdata.ProviderModels.EtfProfile;
import com.dca.terminal.marketdata.ProviderModels.IntradayBar;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.ProviderModels.ProviderQuote;
import com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;
import com.dca.terminal.marketdata.ProviderModels.SplitEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Twelve Data adapter for the market-data SPI.
 *
 * <p>The ETF full-data and split endpoints are paid Twelve Data capabilities
 * (currently documented for Ultra/Grow plans). If the configured key cannot
 * access them, the API error is surfaced as a non-retryable provider error;
 * the service can then use its normal provider fallback.</p>
 */
@Component
public class TwelveDataProvider implements MarketDataProvider {
    private static final String DEFAULT_BASE_URL = "https://api.twelvedata.com";
    private static final String EXCHANGE_ZONE = "America/New_York";
    private static final ZoneId EXCHANGE_ZONE_ID = ZoneId.of(EXCHANGE_ZONE);
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    private static final int DEFAULT_TIMEOUT_MS = 5_000;

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final Clock clock;

    /** Keeps direct provider construction compatible with the original adapter stub. */
    public TwelveDataProvider(String apiKey) {
        this(RestClient.builder(), new ObjectMapper(), apiKey, DEFAULT_BASE_URL, DEFAULT_TIMEOUT_MS, false,
                Clock.systemUTC());
    }

    @Autowired
    public TwelveDataProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dca.market-data.twelve-data.api-key:}") String apiKey,
            @Value("${dca.market-data.twelve-data.base-url:https://api.twelvedata.com}") String baseUrl,
            @Value("${dca.market-data.twelve-data.timeout-ms:5000}") int timeoutMs,
            Clock clock) {
        this(builder, objectMapper, apiKey, baseUrl, timeoutMs, false, clock);
    }

    TwelveDataProvider(RestClient.Builder builder, ObjectMapper objectMapper, String apiKey,
                       String baseUrl, int timeoutMs, boolean testConstructor) {
        this(builder, objectMapper, apiKey, baseUrl, timeoutMs, testConstructor, Clock.systemUTC());
    }

    TwelveDataProvider(RestClient.Builder builder, ObjectMapper objectMapper, String apiKey,
                       String baseUrl, int timeoutMs, boolean testConstructor, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        factory.setConnectTimeout(effectiveTimeout);
        factory.setReadTimeout(effectiveTimeout);
        this.client = builder.requestFactory(factory).baseUrl(normalizeBaseUrl(baseUrl)).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ProviderId id() {
        return ProviderId.TWELVE_DATA;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public List<ProviderSearchResult> search(String query) {
        requireConfigured();
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) return List.of();

        JsonNode root = get("/symbol_search", params("symbol", normalized, "outputsize", "30"));
        JsonNode data = root.path("data");
        if (!data.isArray()) return List.of();

        List<ProviderSearchResult> results = new ArrayList<>();
        for (JsonNode item : data) {
            String type = text(item, "instrument_type");
            String symbol = text(item, "symbol");
            if (!"ETF".equalsIgnoreCase(type) || symbol.isBlank()) continue;
            results.add(new ProviderSearchResult(
                    symbol,
                    text(item, "instrument_name", symbol),
                    text(item, "exchange", null),
                    text(item, "currency", "USD"),
                    InstrumentType.ETF));
        }
        return results;
    }

    @Override
    public ProviderQuote getLatestQuote(InstrumentEntity instrument) {
        requireConfigured();
        String symbol = symbolOf(instrument);
        JsonNode root = get("/quote", params(
                "symbol", symbol,
                "interval", "1day",
                "type", "ETF",
                "timezone", "UTC"));

        BigDecimal price = decimal(root, "close");
        if (price == null || price.signum() <= 0) {
            throw new ProviderException(id(), "Twelve Data returned no valid quote for " + symbol, false);
        }
        BigDecimal previousClose = decimal(root, "previous_close");
        Instant marketTimestamp = epoch(root, "last_quote_at");
        if (marketTimestamp == null) marketTimestamp = epoch(root, "timestamp");
        if (marketTimestamp == null) {
            marketTimestamp = parseInstant(text(root, "datetime", null), ZoneId.of(EXCHANGE_ZONE)).orElse(null);
        }
        return new ProviderQuote(price, previousClose, null, null, marketTimestamp, Instant.now());
    }

    @Override
    public List<PriceBar> getHistoricalPrices(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        requireConfigured();
        validateRange(from, to);
        String symbol = symbolOf(instrument);

        // Twelve Data exposes one adjusted close mode per time-series call.
        // Fetch raw prices for OHLC and adjust=all for the return series.
        Map<LocalDate, DailyValue> raw = fetchDaily(symbol, from, to, "none");
        if (raw.isEmpty()) return List.of();
        Map<LocalDate, DailyValue> adjusted = fetchDaily(symbol, from, to, "all");

        return raw.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    DailyValue value = entry.getValue();
                    DailyValue adjustedValue = adjusted.get(entry.getKey());
                    return new PriceBar(entry.getKey(), value.open(), value.high(), value.low(), value.close(),
                            adjustedValue == null ? null : adjustedValue.close(), value.volume());
                })
                .toList();
    }

    @Override
    public List<IntradayBar> getIntradayPrices(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        requireConfigured();
        validateRange(from, to);
        String symbol = symbolOf(instrument);
        JsonNode root = get("/time_series", timeSeriesParams(symbol, from, to, "5min", "none"));
        JsonNode values = root.path("values");
        if (!values.isArray()) {
            return emptyIntradayOrThrow(symbol, to);
        }

        ZoneId zone = exchangeZone(root.path("meta"));
        List<IntradayBar> bars = new ArrayList<>();
        for (JsonNode value : values) {
            Optional<Instant> timestamp = parseInstant(text(value, "datetime", null), zone);
            if (timestamp.isEmpty()) continue;
            LocalDate tradeDate = timestamp.get().atZone(zone).toLocalDate();
            if (tradeDate.isBefore(from) || tradeDate.isAfter(to)) continue;
            BigDecimal close = decimal(value, "close");
            if (close == null) continue;
            bars.add(new IntradayBar(timestamp.get(), decimal(value, "open"), decimal(value, "high"),
                    decimal(value, "low"), close, longValue(value, "volume")));
        }
        bars.sort(Comparator.comparing(IntradayBar::timestamp));
        if (bars.isEmpty()) return emptyIntradayOrThrow(symbol, to);
        return bars;
    }

    private List<IntradayBar> emptyIntradayOrThrow(String symbol, LocalDate requestedDate) {
        if (currentRegularSessionStarted(requestedDate, clock.instant())) {
            throw new ProviderException(id(),
                    "Twelve Data returned no usable intraday bars after the regular session started for " + symbol,
                    true);
        }
        return List.of();
    }

    static boolean currentRegularSessionStarted(LocalDate requestedDate, Instant now) {
        if (requestedDate == null || now == null) return false;
        LocalDate currentDate = now.atZone(EXCHANGE_ZONE_ID).toLocalDate();
        if (!requestedDate.equals(currentDate)) return false;
        Instant regularStart = requestedDate.atTime(REGULAR_OPEN).atZone(EXCHANGE_ZONE_ID).toInstant();
        return !now.isBefore(regularStart);
    }

    @Override
    public Optional<EtfProfile> getProfile(InstrumentEntity instrument) {
        requireConfigured();
        String symbol = symbolOf(instrument);
        // /etfs/world is Twelve Data's documented ETF full-data endpoint. It
        // is intentionally used only for profile data because it is high cost.
        JsonNode root = get("/etfs/world", params("symbol", symbol, "dp", "11"));
        JsonNode summary = root.path("etf").path("summary");
        if (!summary.isObject()) return Optional.empty();

        return Optional.of(new EtfProfile(
                text(summary, "name", instrument.getName()),
                text(summary, "exchange", instrument.getExchange()),
                text(summary, "currency", instrument.getCurrency()),
                text(summary, "fund_family", instrument.getIssuer()),
                decimal(summary, "expense_ratio_net"),
                decimal(summary, "net_assets"),
                decimal(summary, "yield")));
    }

    @Override
    public List<SplitEvent> getSplits(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        requireConfigured();
        validateRange(from, to);
        String symbol = symbolOf(instrument);
        JsonNode root = get("/splits", params(
                "symbol", symbol,
                "start_date", from.toString(),
                "end_date", to.toString()));
        JsonNode splits = root.path("splits");
        if (!splits.isArray()) return Collections.emptyList();

        List<SplitEvent> events = new ArrayList<>();
        for (JsonNode split : splits) {
            LocalDate date = parseDate(text(split, "date", null));
            if (date == null || date.isBefore(from) || date.isAfter(to)) continue;

            BigDecimal numerator = decimal(split, "from_factor");
            BigDecimal denominator = decimal(split, "to_factor");
            // ratio is documented as the post-split price / pre-split price;
            // use its inverse only for responses missing the explicit factors.
            if ((numerator == null || denominator == null) && decimal(split, "ratio") != null
                    && decimal(split, "ratio").signum() > 0) {
                numerator = BigDecimal.ONE;
                denominator = decimal(split, "ratio");
            }
            if (numerator == null || denominator == null || numerator.signum() <= 0 || denominator.signum() <= 0) {
                continue;
            }
            events.add(new SplitEvent(date, numerator, denominator));
        }
        events.sort(Comparator.comparing(SplitEvent::effectiveDate));
        return events;
    }

    private Map<LocalDate, DailyValue> fetchDaily(String symbol, LocalDate from, LocalDate to, String adjustment) {
        JsonNode root = get("/time_series", timeSeriesParams(symbol, from, to, "1day", adjustment));
        JsonNode values = root.path("values");
        if (!values.isArray()) return Map.of();

        Map<LocalDate, DailyValue> bars = new HashMap<>();
        for (JsonNode value : values) {
            LocalDate date = parseDate(text(value, "datetime", null));
            BigDecimal close = decimal(value, "close");
            if (date == null || close == null || date.isBefore(from) || date.isAfter(to)) continue;
            bars.put(date, new DailyValue(decimal(value, "open"), decimal(value, "high"), decimal(value, "low"),
                    close, longValue(value, "volume")));
        }
        return bars;
    }

    private Map<String, String> timeSeriesParams(String symbol, LocalDate from, LocalDate to,
                                                  String interval, String adjustment) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("interval", interval);
        params.put("start_date", from.toString());
        params.put("end_date", to.toString());
        params.put("outputsize", "5000");
        params.put("order", "asc");
        params.put("adjust", adjustment);
        params.put("type", "ETF");
        if (!"1day".equals(interval)) params.put("timezone", EXCHANGE_ZONE);
        params.put("apikey", apiKey);
        return params;
    }

    private Map<String, String> params(String firstKey, String firstValue, String... rest) {
        Map<String, String> params = new HashMap<>();
        params.put(firstKey, firstValue);
        for (int i = 0; i + 1 < rest.length; i += 2) params.put(rest[i], rest[i + 1]);
        params.put("apikey", apiKey);
        return params;
    }

    private JsonNode get(String path, Map<String, String> params) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
            params.forEach(uri::queryParam);
            URI requestUri = uri.build().encode().toUri();
            String body = client.get().uri(requestUri).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new ProviderException(id(), "Twelve Data returned an empty response", false);
            }
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ProviderException(id(), "Twelve Data returned a non-object response", false);
            }
            ProviderException apiError = apiError(root);
            if (apiError != null) throw apiError;
            return root;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 408 || status == 429 || status >= 500 && status <= 599;
            String message = status == 404
                    ? "Twelve Data symbol or endpoint was not found (HTTP 404)"
                    : "Twelve Data HTTP " + status;
            throw new ProviderException(id(), message, retryable, exception);
        } catch (ResourceAccessException exception) {
            throw new ProviderException(id(), "Twelve Data request timed out or was unreachable", true, exception);
        } catch (ProviderException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ProviderException(id(), "Twelve Data response could not be decoded", false, exception);
        } catch (Exception exception) {
            throw new ProviderException(id(), "Twelve Data request failed", false, exception);
        }
    }

    private ProviderException apiError(JsonNode root) {
        String status = text(root, "status", "");
        JsonNode codeNode = root.get("code");
        String message = text(root, "message", text(root, "error", "Twelve Data returned an error"));
        if (!"error".equalsIgnoreCase(status) && codeNode == null && !root.has("error")) return null;

        int code = codeNode == null ? -1 : integer(codeNode);
        String lower = message.toLowerCase(Locale.ROOT);
        boolean symbolNotFound = lower.contains("symbol") && (lower.contains("not found")
                || lower.contains("invalid") || lower.contains("does not exist") || lower.contains("not available"));
        boolean retryable = !symbolNotFound && (code == 408 || code == 429 || code >= 500 && code <= 599
                || lower.contains("rate limit") || lower.contains("too many request")
                || lower.contains("temporar") || lower.contains("timeout"));
        return new ProviderException(id(), "Twelve Data API error: " + message, retryable);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ProviderException(id(), "Twelve Data is not configured in the backend", false);
        }
    }

    private static String symbolOf(InstrumentEntity instrument) {
        if (instrument == null || instrument.getSymbol() == null || instrument.getSymbol().isBlank()) {
            throw new ProviderException(ProviderId.TWELVE_DATA, "Instrument symbol is required", false);
        }
        return instrument.getSymbol().trim().toUpperCase(Locale.ROOT);
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ProviderException(ProviderId.TWELVE_DATA, "A valid date range is required", false);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        String result = value.asText(fallback);
        return result == null || result.isBlank() ? fallback : result.trim();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.decimalValue();
        if (!value.isTextual()) return null;
        String raw = value.asText().trim();
        if (raw.isBlank() || raw.equalsIgnoreCase("n/a") || raw.equalsIgnoreCase("none")
                || raw.equalsIgnoreCase("null") || raw.equals("-")) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long longValue(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        return value == null ? null : value.longValue();
    }

    private static int integer(JsonNode node) {
        try {
            return node.isNumber() ? node.intValue() : Integer.parseInt(node.asText());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static Instant epoch(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        if (value == null) return null;
        try {
            return Instant.ofEpochSecond(value.longValueExact());
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() < 10) return null;
        try {
            return LocalDate.parse(normalized.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Optional<Instant> parseInstant(String value, ZoneId zone) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.trim();
        try {
            if (normalized.matches("\\d+")) return Optional.of(Instant.ofEpochSecond(Long.parseLong(normalized)));
            if (normalized.length() == 10) return Optional.of(LocalDate.parse(normalized).atStartOfDay(zone).toInstant());
            if (normalized.endsWith("Z") || normalized.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return Optional.of(Instant.parse(normalized.replace(' ', 'T')));
            }
            LocalDateTime local = LocalDateTime.parse(normalized.replace(' ', 'T'));
            return Optional.of(local.atZone(zone).toInstant());
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static ZoneId exchangeZone(JsonNode meta) {
        String value = text(meta, "exchange_timezone", EXCHANGE_ZONE);
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of(EXCHANGE_ZONE);
        }
    }

    private record DailyValue(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume) { }
}
