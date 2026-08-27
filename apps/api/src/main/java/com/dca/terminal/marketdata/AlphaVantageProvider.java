package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
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
import java.time.LocalDate;
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
 * Alpha Vantage adapter limited to the documented ETF_PROFILE capability.
 * Alpha Vantage has other market-data functions, but this v1 provider is
 * intentionally metadata-only so it cannot silently become a second price
 * source with different adjustment and freshness semantics.
 */
@Component
public class AlphaVantageProvider implements MarketDataProvider {
    private static final String DEFAULT_BASE_URL = "https://www.alphavantage.co";
    private static final int DEFAULT_TIMEOUT_MS = 5_000;

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    /** Keeps direct provider construction compatible with the original adapter stub. */
    public AlphaVantageProvider(String apiKey) {
        this(RestClient.builder(), new ObjectMapper(), apiKey, DEFAULT_BASE_URL, DEFAULT_TIMEOUT_MS, false);
    }

    @Autowired
    public AlphaVantageProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dca.market-data.alpha-vantage.api-key:}") String apiKey,
            @Value("${dca.market-data.alpha-vantage.base-url:https://www.alphavantage.co}") String baseUrl,
            @Value("${dca.market-data.alpha-vantage.timeout-ms:5000}") int timeoutMs) {
        this(builder, objectMapper, apiKey, baseUrl, timeoutMs, false);
    }

    AlphaVantageProvider(RestClient.Builder builder, ObjectMapper objectMapper, String apiKey,
                         String baseUrl, int timeoutMs, boolean testConstructor) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        factory.setConnectTimeout(effectiveTimeout);
        factory.setReadTimeout(effectiveTimeout);
        this.client = builder.requestFactory(factory).baseUrl(normalizeBaseUrl(baseUrl)).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public ProviderId id() {
        return ProviderId.ALPHA_VANTAGE;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public List<ProviderSearchResult> search(String query) {
        throw unsupported("symbol search");
    }

    @Override
    public ProviderQuote getLatestQuote(InstrumentEntity instrument) {
        throw unsupported("latest quote");
    }

    @Override
    public List<PriceBar> getHistoricalPrices(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        throw unsupported("daily history");
    }

    @Override
    public List<IntradayBar> getIntradayPrices(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        throw unsupported("intraday history");
    }

    @Override
    public Optional<EtfProfile> getProfile(InstrumentEntity instrument) {
        requireConfigured();
        if (instrument == null || instrument.getSymbol() == null || instrument.getSymbol().isBlank()) {
            throw new ProviderException(id(), "Instrument symbol is required", false);
        }
        String symbol = instrument.getSymbol().trim().toUpperCase(Locale.ROOT);
        JsonNode root = get("/query", params("function", "ETF_PROFILE", "symbol", symbol));

        BigDecimal assets = decimal(root, "net_assets");
        BigDecimal expenseRatio = decimal(root, "net_expense_ratio");
        BigDecimal dividendYield = decimal(root, "dividend_yield");
        if (assets == null && expenseRatio == null && dividendYield == null) return Optional.empty();

        // ETF_PROFILE supplies fund metrics/holdings, but not a canonical
        // display name, exchange, currency, or issuer. Preserve those facts
        // from InstrumentEntity and only enrich the fields Alpha provides.
        return Optional.of(new EtfProfile(instrument.getName(), instrument.getExchange(), instrument.getCurrency(),
                instrument.getIssuer(), expenseRatio, assets, dividendYield));
    }

    @Override
    public List<SplitEvent> getSplits(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        throw unsupported("split events");
    }

    private JsonNode get(String path, Map<String, String> params) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
            params.forEach(uri::queryParam);
            URI requestUri = uri.build().encode().toUri();
            String body = client.get().uri(requestUri).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new ProviderException(id(), "Alpha Vantage returned an empty response", false);
            }
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ProviderException(id(), "Alpha Vantage returned a non-object response", false);
            }
            ProviderException apiError = apiError(root);
            if (apiError != null) throw apiError;
            return root;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 408 || status == 429 || status >= 500 && status <= 599;
            throw new ProviderException(id(), "Alpha Vantage HTTP " + status, retryable, exception);
        } catch (ResourceAccessException exception) {
            throw new ProviderException(id(), "Alpha Vantage request timed out or was unreachable", true, exception);
        } catch (ProviderException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ProviderException(id(), "Alpha Vantage response could not be decoded", false, exception);
        } catch (Exception exception) {
            throw new ProviderException(id(), "Alpha Vantage request failed", false, exception);
        }
    }

    private ProviderException apiError(JsonNode root) {
        String key = null;
        for (String candidate : List.of("Error Message", "Note", "Information")) {
            if (root.has(candidate)) {
                key = candidate;
                break;
            }
        }
        if (key == null) return null;

        String message = text(root, key, "Alpha Vantage returned an error");
        String lower = message.toLowerCase(Locale.ROOT);
        boolean retryable = lower.contains("rate limit") || lower.contains("too many request")
                || lower.contains("frequency") || lower.contains("temporar") || lower.contains("try again")
                || lower.contains("throttle") || "Note".equals(key) && lower.contains("thank you");
        return new ProviderException(id(), "Alpha Vantage API error: " + message, retryable);
    }

    private ProviderException unsupported(String capability) {
        return new ProviderException(id(), "Alpha Vantage provider does not support " + capability + " in v1", false);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ProviderException(id(), "Alpha Vantage is not configured in the backend", false);
        }
    }

    private Map<String, String> params(String firstKey, String firstValue, String... rest) {
        Map<String, String> params = new HashMap<>();
        params.put(firstKey, firstValue);
        for (int i = 0; i + 1 < rest.length; i += 2) params.put(rest[i], rest[i + 1]);
        params.put("apikey", apiKey);
        return params;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return fallback;
        String result = value.asText(fallback);
        return result == null || result.isBlank() ? fallback : result.trim();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
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
}
