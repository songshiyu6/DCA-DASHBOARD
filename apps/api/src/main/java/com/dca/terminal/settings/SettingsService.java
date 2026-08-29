package com.dca.terminal.settings;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.marketdata.MarketDataDtos.ProviderStatus;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.settings.SettingsDtos.SettingsResponse;
import static com.dca.terminal.settings.SettingsDtos.SettingsUpdateRequest;

@Service
public class SettingsService {
    static final String PRIMARY_PROVIDER = "primaryProvider";
    static final String FALLBACK_PROVIDER = "fallbackProvider";
    static final String THEME = "theme";

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
        Map<String, Boolean> configured = marketDataService.providerStatuses().stream()
                .collect(Collectors.toMap(ProviderStatus::id, ProviderStatus::configured, (first, ignored) -> first));
        return new SettingsResponse("USD", primary, fallback, configured.getOrDefault(ProviderId.TWELVE_DATA.name(), false),
                configured.getOrDefault(ProviderId.ALPHA_VANTAGE.name(), false), theme);
    }

    @Transactional
    @CacheEvict(cacheNames = {"quotes", "search"}, allEntries = true)
    public SettingsResponse update(SettingsUpdateRequest request) {
        if (request == null) throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_SETTINGS", "Settings body is required");
        SettingsResponse previous = request.primaryProvider() != null || request.fallbackProvider() != null ? get() : null;
        if (request.primaryProvider() != null) save(PRIMARY_PROVIDER, provider(request.primaryProvider(), null, false));
        if (request.fallbackProvider() != null) save(FALLBACK_PROVIDER, provider(request.fallbackProvider(), null, true));
        if (request.theme() != null) save(THEME, theme(request.theme()));
        SettingsResponse updated = get();
        if (previous != null && (!previous.primaryProvider().equals(updated.primaryProvider())
                || !previous.fallbackProvider().equals(updated.fallbackProvider()))) {
            snapshotInvalidator.invalidateAll();
        }
        return updated;
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
}
