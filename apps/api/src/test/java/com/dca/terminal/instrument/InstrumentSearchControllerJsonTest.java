package com.dca.terminal.instrument;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.marketdata.MarketDataService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static com.dca.terminal.instrument.InstrumentDtos.SearchResult;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InstrumentController.class)
@AutoConfigureMockMvc(addFilters = false)
class InstrumentSearchControllerJsonTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MarketDataService marketDataService;

    @Test
    void returnsProviderOrCanonicalSearchFieldsWithoutFlatteningTheResult() throws Exception {
        when(marketDataService.search("QQQ")).thenReturn(List.of(
                new SearchResult("QQQ", "Invesco QQQ Trust", "NASDAQ", "USD", InstrumentType.ETF)));

        mockMvc.perform(get("/api/v1/instruments/search").param("q", "QQQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("QQQ"))
                .andExpect(jsonPath("$[0].name").value("Invesco QQQ Trust"))
                .andExpect(jsonPath("$[0].exchange").value("NASDAQ"))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].instrumentType").value("ETF"));
    }

    @Test
    void preservesUnavailableProviderAsServiceUnavailable() throws Exception {
        when(marketDataService.search("UNKNOWN")).thenThrow(new DomainException(
                HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE", "ETF search is temporarily unavailable"));

        mockMvc.perform(get("/api/v1/instruments/search").param("q", "UNKNOWN"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"));
    }
}
