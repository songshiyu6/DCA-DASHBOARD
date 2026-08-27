package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentType;
import com.dca.terminal.marketdata.ProviderModels.EtfProfile;
import com.dca.terminal.marketdata.ProviderModels.IntradayBar;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.ProviderModels.ProviderQuote;
import com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;
import com.dca.terminal.marketdata.ProviderModels.SplitEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class YahooFinanceProvider implements MarketDataProvider {
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36";
    private static final Logger log = LoggerFactory.getLogger(YahooFinanceProvider.class);
    private final RestClient client;
    private final ObjectMapper objectMapper;

    /** Keeps direct provider construction compatible with unit tests and small integrations. */
    public YahooFinanceProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dca.market-data.yahoo.base-url}") String baseUrl,
            @Value("${dca.market-data.yahoo.timeout-ms:5000}") int timeoutMs) {
        this(builder, objectMapper, baseUrl, timeoutMs, "");
    }

    @Autowired
    public YahooFinanceProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dca.market-data.yahoo.base-url}") String baseUrl,
            @Value("${dca.market-data.yahoo.timeout-ms:5000}") int timeoutMs,
            @Value("${dca.market-data.yahoo.proxy-url:}") String proxyUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        Proxy proxy = proxyFor(proxyUrl);
        if (proxy != null) factory.setProxy(proxy);
        this.client = builder
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
        this.objectMapper = objectMapper;
        log.info("Yahoo market-data client initialized host={} httpVersion=HTTP_1_1 proxyConfigured={}",
                URI.create(baseUrl).getHost(), proxy != null);
    }

    @Override
    public ProviderId id() { return ProviderId.YAHOO; }

    @Override
    public boolean isConfigured() { return true; }

    @Override
    public List<ProviderSearchResult> search(String query) {
        // Yahoo's full search endpoint is frequently rate-limited. The
        // autocomplete endpoint provides the same ticker directory data and
        // is the endpoint used for the add-ETF lookup.
        JsonNode root = get("/v6/finance/autocomplete", java.util.Map.of(
                "query", query,
                "lang", "en-US",
                "region", "US"));
        List<ProviderSearchResult> results = new ArrayList<>();
        for (JsonNode result : root.path("ResultSet").path("Result")) {
            String type = text(result, "typeDisp", text(result, "type", ""));
            String symbol = text(result, "symbol");
            if (!symbol.isBlank() && "ETF".equalsIgnoreCase(type)) {
                results.add(new ProviderSearchResult(symbol, text(result, "name", symbol),
                        text(result, "exchDisp", text(result, "exch", null)),
                        "USD", InstrumentType.ETF));
            }
        }
        return results;
    }

    @Override
    public ProviderQuote getLatestQuote(InstrumentEntity instrument) {
        JsonNode result = chart(instrument.getSymbol(), "5d", "1d", null, null);
        JsonNode meta = chartResult(result).path("meta");
        BigDecimal price = decimal(meta, "regularMarketPrice");
        BigDecimal previousClose = decimal(meta, "previousClose");
        if (previousClose == null) previousClose = decimal(meta, "chartPreviousClose");
        if (price == null || price.signum() <= 0) {
            throw new ProviderException(id(), "Yahoo returned no valid regular market price", false);
        }
        Instant timestamp = instant(meta, "regularMarketTime");
        return new ProviderQuote(price, previousClose, decimal(meta, "bid"), decimal(meta, "ask"),
                        timestamp, Instant.now());
    }

    @Override
    public List<PriceBar> getHistoricalPrices(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        JsonNode result = chart(instrument.getSymbol(), null, "1d", from, to.plusDays(1));
        JsonNode chart = chartResult(result);
        JsonNode timestamps = chart.path("timestamp");
        JsonNode quote = chart.path("indicators").path("quote").path(0);
        JsonNode adjusted = chart.path("indicators").path("adjclose").path(0).path("adjclose");
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            Instant timestamp = Instant.ofEpochSecond(timestamps.get(i).asLong());
            LocalDate date = timestamp.atZone(ZoneOffset.UTC).toLocalDate();
            if (date.isBefore(from) || date.isAfter(to)) continue;
            BigDecimal close = decimalAt(quote.path("close"), i);
            if (close == null) continue;
            bars.add(new PriceBar(date, decimalAt(quote.path("open"), i), decimalAt(quote.path("high"), i),
                    decimalAt(quote.path("low"), i), close, decimalAt(adjusted, i), longAt(quote.path("volume"), i)));
        }
        return bars;
    }

    @Override
    public List<IntradayBar> getIntradayPrices(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        JsonNode result = chart(instrument.getSymbol(), null, "5m", from, to.plusDays(1));
        JsonNode chart = chartResult(result);
        JsonNode timestamps = chart.path("timestamp");
        JsonNode quote = chart.path("indicators").path("quote").path(0);
        ZoneId exchangeZone = exchangeZone(chart.path("meta"));
        List<IntradayBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            Instant timestamp = Instant.ofEpochSecond(timestamps.get(i).asLong());
            LocalDate tradeDate = timestamp.atZone(exchangeZone).toLocalDate();
            if (tradeDate.isBefore(from) || tradeDate.isAfter(to)) continue;
            BigDecimal close = decimalAt(quote.path("close"), i);
            if (close != null) {
                bars.add(new IntradayBar(timestamp, decimalAt(quote.path("open"), i),
                        decimalAt(quote.path("high"), i), decimalAt(quote.path("low"), i), close,
                        longAt(quote.path("volume"), i)));
            }
        }
        return bars;
    }

    @Override
    public Optional<EtfProfile> getProfile(InstrumentEntity instrument) {
        try {
            JsonNode root = get("/v10/finance/quoteSummary/" + instrument.getSymbol(),
                    java.util.Map.of("modules", "assetProfile,summaryDetail,defaultKeyStatistics"));
            JsonNode result = root.path("quoteSummary").path("result").path(0);
            if (result.isMissingNode()) return Optional.empty();
            JsonNode detail = result.path("summaryDetail");
            JsonNode stats = result.path("defaultKeyStatistics");
            JsonNode profile = result.path("assetProfile");
            return Optional.of(new EtfProfile(
                    instrument.getName(),
                    instrument.getExchange(), text(detail, "currency", instrument.getCurrency()),
                    text(profile, "fundFamily", instrument.getIssuer()),
                    decimalValue(detail, "annualReportExpenseRatio"),
                    decimalValue(stats, "totalAssets"),
                    decimalValue(detail, "yield")));
        } catch (ProviderException exception) {
            return Optional.empty();
        }
    }

    @Override
    public List<SplitEvent> getSplits(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        JsonNode result = chart(instrument.getSymbol(), null, "1d", from, to.plusDays(1), "splits");
        JsonNode splits = chartResult(result).path("events").path("splits");
        if (!splits.isObject()) return Collections.emptyList();
        List<SplitEvent> events = new ArrayList<>();
        var entries = splits.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            try {
                JsonNode value = entry.getValue();
                LocalDate effectiveDate = Instant.ofEpochSecond(Long.parseLong(entry.getKey()))
                        .atZone(ZoneOffset.UTC).toLocalDate();
                if (effectiveDate.isBefore(from) || effectiveDate.isAfter(to)) continue;
                BigDecimal numerator = decimal(value, "numerator");
                BigDecimal denominator = decimal(value, "denominator");
                if (numerator == null || denominator == null
                        || numerator.signum() <= 0 || denominator.signum() <= 0) {
                    throw new IllegalArgumentException("invalid split event");
                }
                events.add(new SplitEvent(effectiveDate, numerator, denominator));
            } catch (RuntimeException exception) {
                throw new ProviderException(id(), "Yahoo split response could not be decoded", false, exception);
            }
        }
        return events;
    }

    private JsonNode chartResult(JsonNode root) {
        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.size() == 0 || !result.get(0).isObject()) {
            throw new ProviderException(id(), "Yahoo returned an empty or invalid chart", false);
        }
        return result.get(0);
    }

    private JsonNode chart(String symbol, String range, String interval, LocalDate from, LocalDate to) {
        return chart(symbol, range, interval, from, to, "div,splits");
    }

    private JsonNode chart(String symbol, String range, String interval, LocalDate from, LocalDate to, String events) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("interval", interval);
        params.put("events", events);
        if (range != null) {
            params.put("range", range);
        } else {
            params.put("period1", String.valueOf(from.atStartOfDay(ZoneOffset.UTC).toEpochSecond()));
            params.put("period2", String.valueOf(to.atStartOfDay(ZoneOffset.UTC).toEpochSecond()));
        }
        return get("/v8/finance/chart/" + symbol, params);
    }

    private JsonNode get(String path, java.util.Map<String, String> params) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
            params.forEach(uri::queryParam);
            URI requestUri = uri.build().encode().toUri();
            JsonNode body = client.get().uri(requestUri).retrieve().body(JsonNode.class);
            if (body == null || body.path("chart").path("error").isObject()) {
                throw new ProviderException(id(), "Yahoo returned an empty or invalid response", false);
            }
            return body;
        } catch (RestClientResponseException exception) {
            boolean retryable = exception.getStatusCode().value() == 429 || exception.getStatusCode().is5xxServerError();
            throw new ProviderException(id(), "Yahoo HTTP " + exception.getStatusCode().value(), retryable, exception);
        } catch (ResourceAccessException exception) {
            throw new ProviderException(id(), "Yahoo request timed out or was unreachable", true, exception);
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException(id(), "Yahoo response could not be decoded", false, exception);
        }
    }

    private static Proxy proxyFor(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) return null;
        URI uri = URI.create(proxyUrl.trim());
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() <= 0
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Yahoo proxy URL must be an unauthenticated HTTP host:port URL");
        }
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort()));
    }

    private static String text(JsonNode node, String field) { return text(node, field, null); }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.decimalValue();
    }

    private static BigDecimal decimalValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isObject() && value.has("raw")) return decimal(value, "raw");
        return decimal(node, field);
    }

    private static BigDecimal decimalAt(JsonNode array, int index) {
        if (!array.isArray() || index >= array.size() || array.get(index).isNull()) return null;
        return array.get(index).isNumber() ? array.get(index).decimalValue() : null;
    }

    private static Long longAt(JsonNode array, int index) {
        if (!array.isArray() || index >= array.size() || array.get(index).isNull()) return null;
        return array.get(index).isNumber() ? array.get(index).longValue() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Instant.ofEpochSecond(value.asLong()) : null;
    }

    private static ZoneId exchangeZone(JsonNode node) {
        String name = text(node, "exchangeTimezoneName", "UTC");
        try {
            return ZoneId.of(name);
        } catch (RuntimeException ignored) {
            return ZoneOffset.UTC;
        }
    }
}
