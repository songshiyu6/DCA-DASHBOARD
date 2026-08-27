package com.dca.terminal.settings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettingsControllerJsonTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SettingsService settingsService;

    @Test
    void getReturnsSettingsContractWithoutSecrets() throws Exception {
        when(settingsService.get()).thenReturn(settings("DARK", "America/New_York", true, false));

        mockMvc.perform(get("/api/v1/settings").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.primaryProvider").value("YAHOO"))
                .andExpect(jsonPath("$.fallbackProvider").value("TWELVE_DATA"))
                .andExpect(jsonPath("$.twelveDataConfigured").value(true))
                .andExpect(jsonPath("$.alphaVantageConfigured").value(false))
                .andExpect(jsonPath("$.theme").value("DARK"))
                .andExpect(jsonPath("$.timezone").value("America/New_York"))
                .andExpect(jsonPath("$.twelveDataApiKey").doesNotExist())
                .andExpect(jsonPath("$.alphaVantageApiKey").doesNotExist());
    }

    @Test
    void putAcceptsSettingsRequestAndReturnsUpdatedContract() throws Exception {
        when(settingsService.update(org.mockito.ArgumentMatchers.any(SettingsDtos.SettingsUpdateRequest.class)))
                .thenReturn(settings("LIGHT", "UTC", false, true));

        mockMvc.perform(put("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "primaryProvider": "YAHOO",
                                  "fallbackProvider": "ALPHA_VANTAGE",
                                  "theme": "LIGHT",
                                  "timezone": "UTC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.primaryProvider").value("YAHOO"))
                .andExpect(jsonPath("$.fallbackProvider").value("TWELVE_DATA"))
                .andExpect(jsonPath("$.twelveDataConfigured").value(false))
                .andExpect(jsonPath("$.alphaVantageConfigured").value(true))
                .andExpect(jsonPath("$.theme").value("LIGHT"))
                .andExpect(jsonPath("$.timezone").value("UTC"));

        ArgumentCaptor<SettingsDtos.SettingsUpdateRequest> request =
                ArgumentCaptor.forClass(SettingsDtos.SettingsUpdateRequest.class);
        verify(settingsService).update(request.capture());
        assertEquals("YAHOO", request.getValue().primaryProvider());
        assertEquals("ALPHA_VANTAGE", request.getValue().fallbackProvider());
        assertEquals("LIGHT", request.getValue().theme());
        assertEquals("UTC", request.getValue().timezone());
    }

    private static SettingsDtos.SettingsResponse settings(String theme, String timezone,
                                                           boolean twelveDataConfigured,
                                                           boolean alphaVantageConfigured) {
        return new SettingsDtos.SettingsResponse("USD", "YAHOO", "TWELVE_DATA",
                twelveDataConfigured, alphaVantageConfigured, theme, timezone);
    }
}
