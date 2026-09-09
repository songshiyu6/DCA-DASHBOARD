package com.dca.terminal.fund;

import com.dca.terminal.common.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.HtmlUtils;

@Service
public class EastMoneyFundLookupService {
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SOURCE = "EASTMONEY";
    private static final Pattern MANAGEMENT_FEE = Pattern.compile("管理费率\\s*([0-9]+(?:\\.[0-9]+)?)%");
    private static final Pattern CONFIRMATION_DAYS = Pattern.compile("买入确认日\\s*T\\+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCEPTION_DATE = Pattern.compile("成立日期(?:/规模)?[:：]?\\s*(\\d{4})[-年](\\d{1,2})[-月](\\d{1,2})日?");
    private static final Pattern SHARE_CLASS = Pattern.compile("(?:\\)|）)?([A-Z])$");
    private static final Set<String> KNOWN_SHARE_CLASSES = Set.of("A", "C");

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final ChinaFundDataProvider provider;
    private final Clock clock;
    private final String siteBaseUrl;
    private final String f10BaseUrl;

    public EastMoneyFundLookupService(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            ChinaFundDataProvider provider,
            Clock clock,
            @Value("${dca.fund-data.eastmoney.site-base-url:https://fund.eastmoney.com}") String siteBaseUrl,
            @Value("${dca.fund-data.eastmoney.f10-base-url:https://fundf10.eastmoney.com}") String f10BaseUrl,
            @Value("${dca.fund-data.eastmoney.timeout-ms:5000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = builder
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
        this.provider = provider;
        this.clock = clock;
        this.siteBaseUrl = stripTrailingSlash(siteBaseUrl);
        this.f10BaseUrl = stripTrailingSlash(f10BaseUrl);
    }

    public FundLookupResponse lookup(String requestedCode) {
        String code = normalizeCode(requestedCode);
        FundMasterRow master = lookupMaster(code);
        String feeText = feePageText(code);

        BigDecimal managementFeeRate = parseManagementFee(feeText);
        Integer confirmationTradingDays = parseConfirmationDays(feeText);
        LocalDate inceptionDate = parseInceptionDate(feeText);
        String shareClass = inferShareClass(master.name());

        LatestNav latestNav = latestNav(code);
        return new FundLookupResponse(
                code,
                master.name(),
                master.fundType(),
                managementFeeRate,
                confirmationTradingDays,
                shareClass,
                inceptionDate,
                latestNav == null ? null : latestNav.date(),
                latestNav == null ? null : latestNav.nav(),
                latestNav != null,
                SOURCE);
    }

    private FundMasterRow lookupMaster(String code) {
        String body;
        try {
            body = client.get()
                    .uri(URI.create(siteBaseUrl + "/js/fundcode_search.js"))
                    .header("Referer", siteBaseUrl + "/")
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException exception) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FUND_LOOKUP_PROVIDER_UNAVAILABLE",
                    "China fund lookup provider is unavailable");
        }
        if (body == null || body.isBlank()) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FUND_LOOKUP_PROVIDER_UNAVAILABLE",
                    "China fund lookup provider returned an empty response");
        }

        int start = body.indexOf('[');
        int end = body.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FUND_LOOKUP_SCHEMA_CHANGED",
                    "China fund lookup provider response schema changed");
        }
        try {
            JsonNode rows = objectMapper.readTree(body.substring(start, end + 1));
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (!row.isArray() || row.size() < 4 || !code.equals(row.path(0).asText())) continue;
                    String name = row.path(2).asText("").trim();
                    String fundType = row.path(3).asText("").trim();
                    if (!name.isEmpty()) return new FundMasterRow(name, fundType.isEmpty() ? null : fundType);
                }
            }
        } catch (Exception exception) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FUND_LOOKUP_SCHEMA_CHANGED",
                    "China fund lookup provider response schema changed");
        }
        throw new DomainException(HttpStatus.NOT_FOUND, "FUND_LOOKUP_NOT_FOUND", "Fund not found: " + code);
    }

    private String feePageText(String code) {
        try {
            String html = client.get()
                    .uri(URI.create(f10BaseUrl + "/jjfl_" + code + ".html"))
                    .header("Referer", siteBaseUrl + "/" + code + ".html")
                    .retrieve()
                    .body(String.class);
            if (html == null || html.isBlank()) return "";
            String withoutScripts = html
                    .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?s)<[^>]+>", " ");
            return HtmlUtils.htmlUnescape(withoutScripts)
                    .replace('\u00a0', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (RestClientException exception) {
            return "";
        }
    }

    private LatestNav latestNav(String code) {
        LocalDate endDate = LocalDate.now(clock.withZone(CHINA_ZONE));
        try {
            List<ChinaFundDataProvider.NavPoint> points = provider.navHistory(code, endDate.minusDays(45), endDate);
            if (points.isEmpty()) return null;
            ChinaFundDataProvider.NavPoint point = points.getLast();
            return new LatestNav(point.date(), point.nav());
        } catch (FundDataProviderException exception) {
            return null;
        }
    }

    private BigDecimal parseManagementFee(String text) {
        Matcher matcher = MANAGEMENT_FEE.matcher(text);
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group(1)).movePointLeft(2);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Integer parseConfirmationDays(String text) {
        Matcher matcher = CONFIRMATION_DAYS.matcher(text);
        if (!matcher.find()) return null;
        try {
            int days = Integer.parseInt(matcher.group(1));
            return days >= 0 && days <= 10 ? days : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private LocalDate parseInceptionDate(String text) {
        Matcher matcher = INCEPTION_DATE.matcher(text);
        if (!matcher.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String inferShareClass(String name) {
        Matcher matcher = SHARE_CLASS.matcher(name.trim().toUpperCase(Locale.ROOT));
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        return KNOWN_SHARE_CLASSES.contains(value) ? value : null;
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim();
        if (!normalized.matches("\\d{6}")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FUND_LOOKUP_CODE",
                    "Automatic China fund lookup requires a six-digit fund code");
        }
        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (result.isEmpty()) throw new IllegalArgumentException("EastMoney base URL is required");
        return result;
    }

    private record FundMasterRow(String name, String fundType) { }
    private record LatestNav(LocalDate date, BigDecimal nav) { }

    public record FundLookupResponse(
            String code,
            String name,
            String fundType,
            BigDecimal managementFeeRate,
            Integer confirmationTradingDays,
            String shareClass,
            LocalDate inceptionDate,
            LocalDate latestNavDate,
            BigDecimal latestNav,
            boolean historicalNavAvailable,
            String source) { }
}
