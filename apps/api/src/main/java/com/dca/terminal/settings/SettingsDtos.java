package com.dca.terminal.settings;

public final class SettingsDtos {
    private SettingsDtos() { }

    public record SettingsResponse(String baseCurrency, String primaryProvider, String fallbackProvider,
                                    boolean twelveDataConfigured, boolean alphaVantageConfigured,
                                    String theme) { }

    public record SettingsUpdateRequest(String primaryProvider, String fallbackProvider,
                                        String theme) { }
}
