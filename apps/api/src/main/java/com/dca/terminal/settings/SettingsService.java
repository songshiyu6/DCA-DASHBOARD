package com.dca.terminal.settings;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.marketdata.MarketDataDtos.ProviderStatus;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.settings.SettingsDtos.SettingsResponse;
import static com.dca.terminal.settings.SettingsDtos.SettingsUpdateRequest;

@Service
public class SettingsService {
    static final String PRIMARY_PROVIDER = "primaryProvider";
    static final String FALLBACK_PROVIDER = "fallbackProvider";
    static final String THEME = "theme";
    static final String MARKET_TIMEZONE = "marketTimezone";
    static final String DISPLAY_TIMEZONE = "displayTimezone";
    static final String DEFAULT_MARKET_TIMEZONE = "America/New_York";
    static final String DEFAULT_DISPLAY_TIMEZONE = "Asia/Shanghai";
    private static final Set<String> ALLOWED_TIMEZONES = Set.of(DEFAULT_MARKET_TIMEZONE, DEFAULT_DISPLAY_TIMEZONE);

    private final AppSettingRepository repository;
    private final MarketDataService marketDataService;
    private final PortfolioSnapshotInvalidator snapshotInvalidator;

    public SettingsService(AppSettingRepository repository, MarketDataService marketDataService,
                           PortfolioSnapshotInvalidator snapshotInvalidator) {
        this.repository = repository;
        this.marketDataService = marketDataService;
        this.snapshotInvalidator = snapshotInvalidator;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        Map<String, String> values = repository.findAll().stream()
                .collect(Collectors.toMap(AppSettingEntity::getKey,
                        setting -> setting.getValue() == null ? "" : setting.getValue(),
                        (first, ignored) -> first));
        String primary = provider(values.get(PRIMARY_PROVIDER), marketDataService.primaryProvider(), false);
        String fallback = provider(values.get(FALLBACK_PROVIDER), marketDataService.fallbackProvider(), true);
        String theme = theme(values.get(THEME));
        String marketTimezone = timezone(values.get(MARKET_TIMEZONE), DEFAULT_MARKET_TIMEZONE);
        String displayTimezone = timezone(values.get(DISPLAY_TIMEZONE), DEFAULT_DISPLAY_TIMEZONE);
        Map<String, Boolean> configured = marketDataService.providerStatuses().stream()
                .collect(Collectors.toMap(ProviderStatus::id, ProviderStatus::configured, (first, ignored) -> first));
        return new SettingsResponse("USD", primary, fallback, configured.getOrDefault(ProviderId.TWELVE_DATA.name(), false),
                configured.getOrDefault(ProviderId.ALPHA_VANTAGE.name(), false), theme, marketTimezone, displayTimezone);
    }

    @Transactional
    @CacheEvict(cacheNames = {"quotes", "search"}, allEntries = true)
    public SettingsResponse update(SettingsUpdateRequest request) {
        if (request == null) throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_SETTINGS", "Settings body is required");
        SettingsResponse previous = request.primaryProvider() != null || request.fallbackProvider() != null || request.marketTimezone() != null ? get() : null;
        if (request.primaryProvider() != null) save(PRIMARY_PROVIDER, provider(request.primaryProvider(), null, false));
        if (request.fallbackProvider() != null) save(FALLBACK_PROVIDER, provider(request.fallbackProvider(), null, true));
        if (request.theme() != null) save(THEME, theme(request.theme()));
        if (request.marketTimezone() != null) save(MARKET_TIMEZONE, timezone(request.marketTimezone(), DEFAULT_MARKET_TIMEZONE));
        if (request.displayTimezone() != null) save(DISPLAY_TIMEZONE, timezone(request.displayTimezone(), DEFAULT_DISPLAY_TIMEZONE));
        SettingsResponse updated = get();
        if (previous != null && (!previous.primaryProvider().equals(updated.primaryProvider())
                || !previous.fallbackProvider().equals(updated.fallbackProvider())
                || !previous.marketTimezone().equals(updated.marketTimezone()))) {
            snapshotInvalidator.invalidateAll();
        }
        return updated;
    }

    public String marketTimezone() {
        return repository.findById(MARKET_TIMEZONE)
                .map(AppSettingEntity::getValue)
                .map(value -> timezone(value, DEFAULT_MARKET_TIMEZONE))
                .orElse(DEFAULT_MARKET_TIMEZONE);
    }

    public String displayTimezone() {
        return repository.findById(DISPLAY_TIMEZONE)
                .map(AppSettingEntity::getValue)
                .map(value -> timezone(value, DEFAULT_DISPLAY_TIMEZONE))
                .orElse(DEFAULT_DISPLAY_TIMEZONE);
    }

    private void save(String key, String value) {
        AppSettingEntity entity = repository.findById(key).orElseGet(() -> new AppSettingEntity(key, value));
        entity.setKey(key);
        entity.setValue(value);
        repository.save(entity);
    }

    private String provider(String value, String fallback, boolean allowNone) {
        String candidate = value == null || value.isBlank() ? fallback : value;
        if (candidate == null || candidate.isBlank()) candidate = allowNone ? "NONE" : ProviderId.YAHOO.name();
        String normalized = candidate.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (allowNone && "NONE".equals(normalized)) return normalized;
        try {
            return ProviderId.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER", "Unsupported market data provider: " + candidate);
        }
    }

    private String theme(String value) {
        String normalized = value == null || value.isBlank() ? "SYSTEM" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("SYSTEM") && !normalized.equals("LIGHT") && !normalized.equals("DARK")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_THEME", "Theme must be SYSTEM, LIGHT or DARK");
        }
        return normalized;
    }

    private String timezone(String value, String fallback) {
        String candidate = value == null || value.isBlank() ? fallback : value.trim();
        if (!ALLOWED_TIMEZONES.contains(candidate)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE",
                    "Timezone must be America/New_York or Asia/Shanghai");
        }
        return candidate;
    }
}
