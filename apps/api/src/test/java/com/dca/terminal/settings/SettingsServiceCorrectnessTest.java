package com.dca.terminal.settings;

import com.dca.terminal.marketdata.MarketDataDtos.ProviderStatus;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettingsServiceCorrectnessTest {
    @ParameterizedTest
    @CsvSource({
            "ALPHA_VANTAGE, TWELVE_DATA",
            "YAHOO, ALPHA_VANTAGE"
    })
    void invalidatesAllWhenProviderPriorityChanges(String primary, String fallback) {
        PortfolioSnapshotInvalidator invalidator = mock(PortfolioSnapshotInvalidator.class);
        AppSettingRepository repository = repository("YAHOO", "TWELVE_DATA");
        SettingsService service = service(repository, invalidator);

        SettingsDtos.SettingsResponse response = service.update(
                new SettingsDtos.SettingsUpdateRequest(primary, fallback, null, null));

        assertEquals(primary, response.primaryProvider());
        assertEquals(fallback, response.fallbackProvider());
        verify(invalidator).invalidateAll();
        InOrder order = inOrder(repository, invalidator);
        order.verify(repository).save(any(AppSettingEntity.class));
        order.verify(invalidator).invalidateAll();
    }

    @Test
    void doesNotInvalidateWhenProviderPriorityIsUnchanged() {
        PortfolioSnapshotInvalidator invalidator = mock(PortfolioSnapshotInvalidator.class);
        SettingsService service = service(repository("YAHOO", "TWELVE_DATA"), invalidator);

        service.update(new SettingsDtos.SettingsUpdateRequest("YAHOO", "TWELVE_DATA", null, null));

        verifyNoInteractions(invalidator);
    }

    private static SettingsService service(AppSettingRepository repository,
                                           PortfolioSnapshotInvalidator invalidator) {
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.primaryProvider()).thenReturn("YAHOO");
        when(marketData.fallbackProvider()).thenReturn("TWELVE_DATA");
        when(marketData.providerStatuses()).thenReturn(List.of(
                new ProviderStatus("TWELVE_DATA", false),
                new ProviderStatus("ALPHA_VANTAGE", false)));
        return new SettingsService(repository, marketData, ZoneId.of("UTC"), invalidator);
    }

    private static AppSettingRepository repository(String primary, String fallback) {
        Map<String, AppSettingEntity> values = new LinkedHashMap<>();
        values.put(SettingsService.PRIMARY_PROVIDER, new AppSettingEntity(SettingsService.PRIMARY_PROVIDER, primary));
        values.put(SettingsService.FALLBACK_PROVIDER, new AppSettingEntity(SettingsService.FALLBACK_PROVIDER, fallback));
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findAll()).thenAnswer(invocation -> List.copyOf(values.values()));
        when(repository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(values.get(invocation.getArgument(0))));
        when(repository.save(any(AppSettingEntity.class))).thenAnswer(invocation -> {
            AppSettingEntity entity = invocation.getArgument(0);
            values.put(entity.getKey(), entity);
            return entity;
        });
        return repository;
    }
}
