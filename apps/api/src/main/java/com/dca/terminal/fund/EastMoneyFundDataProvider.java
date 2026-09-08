package com.dca.terminal.fund;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class EastMoneyFundDataProvider implements ChinaFundDataProvider {
    static final String SOURCE = "EASTMONEY";
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final int NAV_PAGE_SIZE = 20;
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient client;
    private final String navBaseUrl;
    private final String marketBaseUrl;

    public EastMoneyFundDataProvider(
            RestClient.Builder builder,
            @Value("${dca.fund-data.eastmoney.nav-base-url:https://api.fund.eastmoney.com}") String navBaseUrl,
            @Value("${dca.fund-data.eastmoney.market-base-url:https://push2his.eastmoney.com}") String marketBaseUrl,
            @Value("${dca.fund-data.eastmoney.timeout-ms:5000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = builder
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
        this.navBaseUrl = stripTrailingSlash(navBaseUrl);
        this.marketBaseUrl = stripTrailingSlash(marketBaseUrl);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<NavPoint> navHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        TreeMap<LocalDate, BigDecimal> points = new TreeMap<>();
        int pageIndex = 1;
        while (pageIndex <= 1000) {
            URI uri = UriComponentsBuilder.fromUriString(navBaseUrl)
                    .path("/f10/lsjz")
                    .queryParam("fundCode", fundCode)
                    .queryParam("pageIndex", pageIndex)
                    .queryParam("pageSize", NAV_PAGE_SIZE)
                    .queryParam("startDate", startDate)
                    .queryParam("endDate", endDate)
                    .build(true)
                    .toUri();
            JsonNode root = get(uri, "https://fundf10.eastmoney.com/jjjz_" + fundCode + ".html");
            List<NavPoint> page = parseNavPage(root);
            page.forEach(point -> points.put(point.date(), point.nav()));
            int totalCount = root.path("TotalCount").asInt(page.size());
            if (page.isEmpty() || pageIndex * NAV_PAGE_SIZE >= totalCount) break;
            pageIndex++;
        }
        return List.copyOf(points.values());
    }

    @Override
    public List<LocalDate> tradingDays(LocalDate startDate, LocalDate endDate) {
        URI uri = UriComponentsBuilder.fromUriString(marketBaseUrl)
                .path("/api/qt/stock/kline/get")
                .queryParam("secid", "1.000001")
                .queryParam("klt", "101")
                .queryParam("fqt", "0")
                .queryParam("fields1", "f1,f2,f3,f4,f5,f6")
                .queryParam("fields2", "f51,f52,f53,f54,f55,f56")
                .queryParam("beg", COMPACT_DATE.format(startDate))
                .queryParam("end", COMPACT_DATE.format(endDate))
                .queryParam("lmt", "100000")
                .build(true)
                .toUri();
        JsonNode root = get(uri, null);
        return parseTradingDays(root);
    }

    static List<NavPoint> parseNavPage(JsonNode root) {
        List<NavPoint> result = new ArrayList<>();
        JsonNode rows = root.path("Data").path("LSJZList");
        if (!rows.isArray()) return result;
        for (JsonNode row : rows) {
            String dateText = row.path("FSRQ").asText("").trim();
            String navText = row.path("DWJZ").asText("").trim();
            if (dateText.isEmpty() || navText.isEmpty()) continue;
            try {
                BigDecimal nav = new BigDecimal(navText);
                if (nav.signum() > 0) result.add(new NavPoint(LocalDate.parse(dateText), nav));
            } catch (RuntimeException ignored) {
                // Invalid provider rows are omitted rather than converted into financial facts.
            }
        }
        return result;
    }

    static List<LocalDate> parseTradingDays(JsonNode root) {
        List<LocalDate> result = new ArrayList<>();
        JsonNode rows = root.path("data").path("klines");
        if (!rows.isArray()) return result;
        for (JsonNode row : rows) {
            String value = row.asText("");
            int separator = value.indexOf(',');
            String dateText = separator < 0 ? value : value.substring(0, separator);
            try {
                result.add(LocalDate.parse(dateText));
            } catch (RuntimeException ignored) {
                // A malformed market row is not evidence that a date was open.
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private JsonNode get(URI uri, String referer) {
        try {
            RestClient.RequestHeadersSpec<?> request = client.get().uri(uri);
            if (referer != null) request = request.header("Referer", referer);
            JsonNode body = request.retrieve().body(JsonNode.class);
            if (body == null) throw new FundDataProviderException("EastMoney returned an empty response");
            return body;
        } catch (RestClientException exception) {
            throw new FundDataProviderException("EastMoney request failed", exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (result.isEmpty()) throw new IllegalArgumentException("EastMoney base URL is required");
        return result;
    }
}
