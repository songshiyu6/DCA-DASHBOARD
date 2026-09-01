package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentType;
import com.dca.terminal.marketdata.ProviderModels.EtfProfile;
import com.dca.terminal.marketdata.ProviderModels.IntradayBar;
import com.dca.terminal.marketdata.ProviderModels.IntradayResult;
import com.dca.terminal.marketdata.ProviderModels.IntradaySession;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.ProviderModels.ProviderQuote;
import com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;
import com.dca.terminal.marketdata.ProviderModels.SplitEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class YahooFinanceProvider implements MarketDataProvider {
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final String COOKIE_BOOTSTRAP_URL = "https://fc.yahoo.com";
    private static final Duration QUOTE_SESSION_TTL = Duration.ofMinutes(45);
    private static final ZoneId US_MARKET_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    private static final Logger log = LoggerFactory.getLogger(YahooFinanceProvider.class);
    private final RestClient client;
    private final RestClient authClient;
    private final ObjectMapper objectMapper;
    private final boolean authenticatedQuoteEndpoint;
    private final String cookieBootstrapUrl;
    private final Clock clock;
    private final Object quoteSessionLock = new Object();
    private volatile YahooQuoteSession quoteSession;

    /** Keeps direct provider construction compatible with unit tests and small integrations. */
    public YahooFinanceProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dca.market-data.yahoo.base-url}") String baseUrl,
            @Value("${dca.market-data.yahoo.timeout-ms:5000}") int timeoutMs) {
        this(builder, objectMapper, baseUrl, timeoutMs, "", Clock.systemUTC());
    }

    /** Keeps direct provider construction with an explicit proxy compatible with existing tests. */
    public YahooFinanceProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            String baseUrl,
            int timeoutMs,
            String proxyUrl) {
        this(builder, objectMapper, baseUrl, timeoutMs, proxyUrl, Clock.systemUTC());
    }

    @Autowired
    public YahooFinanceProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dca.market-data.yahoo.base-url}") String baseUrl,
            @Value("${dca.market-data.yahoo.timeout-ms:5000}") int timeoutMs,
            @Value("${dca.market-data.yahoo.proxy-url:}") String proxyUrl,
            Clock clock) {
        this(builder, objectMapper, baseUrl, timeoutMs, proxyUrl,
                isOfficialYahooHost(baseUrl), COOKIE_BOOTSTRAP_URL, clock);
    }

    YahooFinanceProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            String baseUrl,
            int timeoutMs,
            String proxyUrl,
            boolean authenticatedQuoteEndpoint,
            String cookieBootstrapUrl) {
        this(builder, objectMapper, baseUrl, timeoutMs, proxyUrl,
                authenticatedQuoteEndpoint, cookieBootstrapUrl, Clock.systemUTC());
    }

    YahooFinanceProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            String baseUrl,
            int timeoutMs,
            String proxyUrl,
            boolean authenticatedQuoteEndpoint,
            String cookieBootstrapUrl,
            Clock clock) {
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
        this.authClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", MediaType.ALL_VALUE)
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
        this.objectMapper = objectMapper;
        this.authenticatedQuoteEndpoint = authenticatedQuoteEndpoint;
        this.cookieBootstrapUrl = cookieBootstrapUrl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        log.info("Yahoo market-data client initialized host={} httpVersion=HTTP_1_1 proxyConfigured={} quoteAuth={}",
                URI.create(baseUrl).getHost(), proxy != null, authenticatedQuoteEndpoint);
    }

    @Override
    public ProviderId id() { return ProviderId.YAHOO; }

    @Override
    public boolean isConfigured() { return true; }

    @Override
    public List<ProviderSearchResult> search(String query) {
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
        try {
            JsonNode quote = quoteResult(instrument.getSymbol());
            QuoteCandidate latest = latestCandidate(quote);
            if (latest == null) {
                throw new ProviderException(id(), "Yahoo returned no valid live quote", false);
            }
            BigDecimal previousClose = decimal(quote, "regularMarketPreviousClose");
            if (previousClose == null) previousClose = decimal(quote, "previousClose");
            if (previousClose == null) {
                try {
                    previousClose = regularChartQuote(instrument, QuoteSession.REGULAR).previousClose();
                } catch (ProviderException ignored) {
                    // A current live price remains useful even if the prior regular close is temporarily unavailable.
                }
            }
            return new ProviderQuote(latest.price(), previousClose, decimal(quote, "bid"), decimal(quote, "ask"),
                    latest.timestamp(), Instant.now(), latest.session());
        } catch (ProviderException exception) {
            log.warn("Yahoo live quote degraded ticker={} reason={} fallback=REGULAR_CHART",
                    instrument.getSymbol(), exception.getMessage());
            return regularChartQuote(instrument, QuoteSession.REGULAR_FALLBACK);
        }
    }

    private ProviderQuote regularChartQuote(InstrumentEntity instrument, QuoteSession session) {
        JsonNode result = chart(instrument.getSymbol(), "5d", "1d", null, null);
        JsonNode chart = chartResult(result);
        JsonNode meta = chart.path("meta");
        BigDecimal price = decimal(meta, "regularMarketPrice");
        Instant timestamp = instant(meta, "regularMarketTime");
        BigDecimal previousClose = decimal(meta, "previousClose");
        if (previousClose == null) previousClose = previousTradingClose(chart, timestamp);
        if (previousClose == null) previousClose = decimal(meta, "chartPreviousClose");
        if (price == null || price.signum() <= 0) {
            throw new ProviderException(id(), "Yahoo returned no valid regular market price", false);
        }
        return new ProviderQuote(price, previousClose, decimal(meta, "bid"), decimal(meta, "ask"),
                timestamp, Instant.now(), session == null ? QuoteSession.REGULAR : session);
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
        IntradayResult result = getIntradayResult(instrument, from, to);
        if (result.bars().isEmpty() && shouldEscalateEmptyIntraday(result, to, clock.instant())) {
            throw new ProviderException(id(), intradayEmptyReason(result), true);
        }
        return result.bars();
    }

    @Override
    public IntradayResult getIntradayResult(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        JsonNode result = intradayChart(instrument.getSymbol(), from, to);
        JsonNode chart = chartResult(result);
        JsonNode meta = chart.path("meta");
        JsonNode timestamps = chart.path("timestamp");
        JsonNode quote = chart.path("indicators").path("quote").path(0);
        ZoneId exchangeZone = exchangeZone(meta);
        IntradaySession session = intradaySession(meta, exchangeZone);
        int rawTimestampCount = timestamps.isArray() ? timestamps.size() : 0;
        int dateMatchedCount = 0;
        int tradingPeriodMatchedCount = 0;
        int nonNullCloseCount = 0;
        List<IntradayBar> bars = new ArrayList<>();
        for (int i = 0; i < rawTimestampCount; i++) {
            JsonNode timestampNode = timestamps.get(i);
            if (timestampNode == null || !timestampNode.isNumber()) continue;
            Instant timestamp = Instant.ofEpochSecond(timestampNode.asLong());
            LocalDate tradeDate = timestamp.atZone(exchangeZone).toLocalDate();
            if (tradeDate.isBefore(from) || tradeDate.isAfter(to)) continue;
            dateMatchedCount++;
            if (!withinCurrentTradingPeriods(meta, timestamp)) continue;
            tradingPeriodMatchedCount++;
            BigDecimal close = decimalAt(quote.path("close"), i);
            if (close == null) continue;
            nonNullCloseCount++;
            bars.add(new IntradayBar(timestamp, decimalAt(quote.path("open"), i),
                    decimalAt(quote.path("high"), i), decimalAt(quote.path("low"), i), close,
                    longAt(quote.path("volume"), i)));
        }
        bars.sort(Comparator.comparing(IntradayBar::timestamp));
        IntradayResult diagnostics = new IntradayResult(bars, rawTimestampCount, dateMatchedCount,
                tradingPeriodMatchedCount, nonNullCloseCount, session);
        log.info("Yahoo intraday diagnostics ticker={} from={} to={} rawTimestamps={} dateMatched={} periodMatched={} closeMatched={} finalBars={} exchangeTimezone={} preStart={} preEnd={} regularStart={} regularEnd={} postStart={} postEnd={}",
                instrument.getSymbol(), from, to, rawTimestampCount, dateMatchedCount, tradingPeriodMatchedCount,
                nonNullCloseCount, bars.size(), session.exchangeTimezoneName(), session.preStart(), session.preEnd(),
                session.regularStart(), session.regularEnd(), session.postStart(), session.postEnd());
        return diagnostics;
    }

    private static boolean shouldEscalateEmptyIntraday(IntradayResult result, LocalDate requestedDate, Instant now) {
        if (result == null || !result.bars().isEmpty()) return false;
        IntradaySession session = result.session();
        Instant regularStart = session == null ? null : session.regularStart();
        if (regularStart == null) {
            regularStart = requestedDate.atTime(REGULAR_OPEN).atZone(US_MARKET_ZONE).toInstant();
        }
        if (now.isBefore(regularStart)) return false;
        return result.rawTimestampCount() == 0
                || result.dateMatchedCount() == 0
                || result.tradingPeriodMatchedCount() == 0
                || result.nonNullCloseCount() == 0;
    }

    private static String intradayEmptyReason(IntradayResult result) {
        if (result.rawTimestampCount() == 0) {
            return "Yahoo returned no intraday timestamps after the regular session started";
        }
        if (result.dateMatchedCount() == 0) {
            return "Yahoo intraday timestamps did not match the requested New York trading date";
        }
        if (result.tradingPeriodMatchedCount() == 0) {
            return "Yahoo intraday timestamps did not match declared trading periods";
        }
        if (result.nonNullCloseCount() == 0) {
            return "Yahoo intraday timestamps had no usable close after the regular session started";
        }
        return "Yahoo intraday response contained no usable current-session bars";
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

    private JsonNode quoteResult(String symbol) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("symbols", symbol);
        params.put("formatted", "false");
        params.put("overnightPrice", "true");
        JsonNode root;
        if (!authenticatedQuoteEndpoint) {
            root = get("/v7/finance/quote", params);
        } else {
            YahooQuoteSession session = quoteSession();
            params.put("crumb", session.crumb());
            try {
                root = get("/v7/finance/quote", params, session.cookie());
            } catch (ProviderException exception) {
                if (!isAuthFailure(exception)) throw exception;
                log.warn("Yahoo quote authentication rejected reason={}; refreshing quote session", exception.getMessage());
                clearQuoteSession();
                session = quoteSession();
                params.put("crumb", session.crumb());
                root = get("/v7/finance/quote", params, session.cookie());
            }
        }
        JsonNode results = root.path("quoteResponse").path("result");
        if (!results.isArray() || results.isEmpty() || !results.get(0).isObject()) {
            throw new ProviderException(id(), "Yahoo returned an empty live quote", false);
        }
        return results.get(0);
    }

    private QuoteCandidate latestCandidate(JsonNode quote) {
        List<QuoteCandidate> candidates = new ArrayList<>();
        addCandidate(candidates, quote, "regularMarketPrice", "regularMarketTime", 0, QuoteSession.REGULAR);
        addCandidate(candidates, quote, "preMarketPrice", "preMarketTime", 1, QuoteSession.PRE_MARKET);
        addCandidate(candidates, quote, "extendedMarketPrice", "extendedMarketTime", 2, QuoteSession.EXTENDED);
        addCandidate(candidates, quote, "postMarketPrice", "postMarketTime", 3, QuoteSession.POST_MARKET);
        addCandidate(candidates, quote, "overnightMarketPrice", "overnightMarketTime", 4, QuoteSession.OVERNIGHT);
        QuoteCandidate timestamped = candidates.stream()
                .filter(candidate -> candidate.timestamp() != null)
                .max(Comparator.comparing(QuoteCandidate::timestamp).thenComparingInt(QuoteCandidate::priority))
                .orElse(null);
        if (timestamped != null) return timestamped;
        return candidates.stream().filter(candidate -> candidate.priority() == 0).findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.getLast());
    }

    private static void addCandidate(List<QuoteCandidate> candidates, JsonNode quote,
                                     String priceField, String timeField, int priority, QuoteSession session) {
        BigDecimal price = decimal(quote, priceField);
        if (price == null || price.signum() <= 0) return;
        candidates.add(new QuoteCandidate(price, instant(quote, timeField), priority, session));
    }

    private YahooQuoteSession quoteSession() {
        YahooQuoteSession existing = quoteSession;
        Instant now = Instant.now();
        if (existing != null && existing.expiresAt().isAfter(now)) return existing;
        synchronized (quoteSessionLock) {
            existing = quoteSession;
            now = Instant.now();
            if (existing != null && existing.expiresAt().isAfter(now)) return existing;
            String cookie = fetchSessionCookie();
            String crumb = fetchCrumb(cookie);
            YahooQuoteSession refreshed = new YahooQuoteSession(cookie, crumb, now.plus(QUOTE_SESSION_TTL));
            quoteSession = refreshed;
            log.debug("Yahoo quote session initialized ttlMinutes={}", QUOTE_SESSION_TTL.toMinutes());
            return refreshed;
        }
    }

    private String fetchSessionCookie() {
        try {
            List<String> setCookies = authClient.get().uri(cookieBootstrapUrl).exchange((request, response) -> {
                List<String> headers = response.getHeaders().get("Set-Cookie");
                return headers == null ? List.of() : List.copyOf(headers);
            });
            String cookie = setCookies.stream()
                    .map(YahooFinanceProvider::cookiePair)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .collect(Collectors.joining("; "));
            if (cookie.isBlank()) {
                throw new ProviderException(id(), "Yahoo quote session cookie was unavailable", true);
            }
            return cookie;
        } catch (ResourceAccessException exception) {
            throw new ProviderException(id(), "Yahoo quote session bootstrap was unreachable", true, exception);
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException(id(), "Yahoo quote session could not be created", true, exception);
        }
    }

    private String fetchCrumb(String cookie) {
        try {
            String crumb = authClient.get().uri("/v1/test/getcrumb")
                    .header("Cookie", cookie)
                    .accept(MediaType.TEXT_PLAIN, MediaType.ALL)
                    .retrieve().body(String.class);
            if (crumb == null || crumb.isBlank() || crumb.length() > 256
                    || crumb.toLowerCase().contains("too many requests") || crumb.contains("<")) {
                throw new ProviderException(id(), "Yahoo returned an invalid quote crumb", true);
            }
            return crumb.trim();
        } catch (RestClientResponseException exception) {
            boolean retryable = exception.getStatusCode().value() == 429 || exception.getStatusCode().is5xxServerError();
            throw new ProviderException(id(), "Yahoo crumb HTTP " + exception.getStatusCode().value(), retryable, exception);
        } catch (ResourceAccessException exception) {
            throw new ProviderException(id(), "Yahoo quote crumb request was unreachable", true, exception);
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException(id(), "Yahoo quote crumb could not be decoded", true, exception);
        }
    }

    private void clearQuoteSession() {
        synchronized (quoteSessionLock) {
            quoteSession = null;
        }
    }

    private static boolean isAuthFailure(ProviderException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("401") || message.contains("403"));
    }

    private static String cookiePair(String setCookie) {
        if (setCookie == null) return "";
        int separator = setCookie.indexOf(';');
        return (separator < 0 ? setCookie : setCookie.substring(0, separator)).trim();
    }

    private static boolean isOfficialYahooHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && (host.equalsIgnoreCase("yahoo.com")
                    || host.toLowerCase().endsWith(".yahoo.com"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private JsonNode chartResult(JsonNode root) {
        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.size() == 0 || !result.get(0).isObject()) {
            throw new ProviderException(id(), "Yahoo returned an empty or invalid chart", false);
        }
        return result.get(0);
    }

    private JsonNode intradayChart(String symbol, LocalDate from, LocalDate to) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("interval", "5m");
        params.put("events", "div,splits");
        params.put("includePrePost", "true");
        params.put("period1", String.valueOf(from.atStartOfDay(US_MARKET_ZONE).toEpochSecond()));
        params.put("period2", String.valueOf(to.plusDays(1).atStartOfDay(US_MARKET_ZONE).toEpochSecond()));
        return get("/v8/finance/chart/" + symbol, params);
    }

    private JsonNode chart(String symbol, String range, String interval, LocalDate from, LocalDate to) {
        return chart(symbol, range, interval, from, to, "div,splits");
    }

    private JsonNode chart(String symbol, String range, String interval, LocalDate from, LocalDate to, String events) {
        return chart(symbol, range, interval, from, to, events, false);
    }

    private JsonNode chart(String symbol, String range, String interval, LocalDate from, LocalDate to,
                           String events, boolean includePrePost) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("interval", interval);
        params.put("events", events);
        if (includePrePost) params.put("includePrePost", "true");
        if (range != null) {
            params.put("range", range);
        } else {
            params.put("period1", String.valueOf(from.atStartOfDay(ZoneOffset.UTC).toEpochSecond()));
            params.put("period2", String.valueOf(to.atStartOfDay(ZoneOffset.UTC).toEpochSecond()));
        }
        return get("/v8/finance/chart/" + symbol, params);
    }

    private JsonNode get(String path, java.util.Map<String, String> params) {
        return get(path, params, null);
    }

    private JsonNode get(String path, java.util.Map<String, String> params, String cookie) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
            params.forEach(uri::queryParam);
            URI requestUri = uri.build().encode().toUri();
            RestClient.RequestHeadersSpec<?> request = client.get().uri(requestUri);
            if (cookie != null && !cookie.isBlank()) request.header("Cookie", cookie);
            JsonNode body = request.retrieve().body(JsonNode.class);
            if (body == null
                    || body.path("chart").path("error").isObject()
                    || body.path("quoteResponse").path("error").isObject()) {
                throw new ProviderException(id(), "Yahoo returned an empty or invalid response", false);
            }
            return body;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 408 || status == 429 || exception.getStatusCode().is5xxServerError();
            throw new ProviderException(id(), "Yahoo HTTP " + status, retryable, exception);
        } catch (ResourceAccessException exception) {
            throw new ProviderException(id(), "Yahoo request timed out or was unreachable", true, exception);
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException(id(), "Yahoo response could not be decoded", false, exception);
        }
    }

    private static boolean withinCurrentTradingPeriods(JsonNode meta, Instant timestamp) {
        JsonNode periods = meta.path("currentTradingPeriod");
        boolean hasValidPeriod = false;
        for (String name : List.of("pre", "regular", "post")) {
            JsonNode period = periods.path(name);
            JsonNode startNode = period.path("start");
            JsonNode endNode = period.path("end");
            if (!startNode.isNumber() || !endNode.isNumber()) continue;
            long start = startNode.asLong();
            long end = endNode.asLong();
            if (end <= start) continue;
            hasValidPeriod = true;
            long value = timestamp.getEpochSecond();
            if (value >= start && value < end) return true;
        }
        return !hasValidPeriod;
    }

    private static IntradaySession intradaySession(JsonNode meta, ZoneId exchangeZone) {
        return new IntradaySession(exchangeZone.getId(),
                periodInstant(meta, "pre", "start"), periodInstant(meta, "pre", "end"),
                periodInstant(meta, "regular", "start"), periodInstant(meta, "regular", "end"),
                periodInstant(meta, "post", "start"), periodInstant(meta, "post", "end"));
    }

    private static Instant periodInstant(JsonNode meta, String periodName, String fieldName) {
        JsonNode value = meta.path("currentTradingPeriod").path(periodName).path(fieldName);
        return value.isNumber() ? Instant.ofEpochSecond(value.asLong()) : null;
    }

    private static BigDecimal previousTradingClose(JsonNode chart, Instant marketTimestamp) {
        JsonNode timestamps = chart.path("timestamp");
        JsonNode closes = chart.path("indicators").path("quote").path(0).path("close");
        if (!timestamps.isArray() || !closes.isArray()) return null;

        Instant latestTimestamp = null;
        BigDecimal latestClose = null;
        Instant priorTimestamp = null;
        BigDecimal priorClose = null;
        int count = Math.min(timestamps.size(), closes.size());
        for (int i = 0; i < count; i++) {
            JsonNode timestampNode = timestamps.get(i);
            BigDecimal close = decimalAt(closes, i);
            if (timestampNode == null || !timestampNode.isNumber() || close == null) continue;
            Instant barTimestamp = Instant.ofEpochSecond(timestampNode.asLong());
            if (latestTimestamp == null || barTimestamp.isAfter(latestTimestamp)) {
                priorTimestamp = latestTimestamp;
                priorClose = latestClose;
                latestTimestamp = barTimestamp;
                latestClose = close;
            } else if (priorTimestamp == null || barTimestamp.isAfter(priorTimestamp)) {
                priorTimestamp = barTimestamp;
                priorClose = close;
            }
        }
        if (latestTimestamp == null) return null;
        if (marketTimestamp == null) return priorClose != null ? priorClose : latestClose;

        ZoneId exchangeZone = exchangeZone(chart.path("meta"));
        LocalDate marketDate = marketTimestamp.atZone(exchangeZone).toLocalDate();
        LocalDate latestTradeDate = latestTimestamp.atZone(exchangeZone).toLocalDate();
        if (latestTradeDate.isBefore(marketDate)) return latestClose;
        return priorClose;
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
        String name = text(node, "exchangeTimezoneName", US_MARKET_ZONE.getId());
        try {
            return ZoneId.of(name);
        } catch (RuntimeException ignored) {
            return US_MARKET_ZONE;
        }
    }

    private record QuoteCandidate(BigDecimal price, Instant timestamp, int priority, QuoteSession session) { }
    private record YahooQuoteSession(String cookie, String crumb, Instant expiresAt) { }
}
