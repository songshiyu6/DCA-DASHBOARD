package com.dca.terminal.instrument;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.config.JacksonConfig;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.MarketDataDtos.MetricsResponse;
import com.dca.terminal.marketdata.MarketDataDtos.PriceHistoryResponse;
import com.dca.terminal.marketdata.MarketDataDtos.PricePoint;
import com.dca.terminal.marketdata.MarketDataDtos.SyncResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.dca.terminal.instrument.InstrumentDtos.SearchResult;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InstrumentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
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

    @Test
    void returnsDailyHistoryEnvelopeWithStatusAndAdjustedClose() throws Exception {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        when(marketDataService.getInstrument("VOO")).thenReturn(instrument);
        when(marketDataService.prices(instrument, "5Y")).thenReturn(new PriceHistoryResponse(
                List.of(new PricePoint("2026-08-27", new java.math.BigDecimal("505.00"),
                        new java.math.BigDecimal("504.50"))),
                FreshnessStatus.FRESH, "YAHOO", LocalDate.of(2026, 8, 27),
                Instant.parse("2026-08-27T20:00:00Z"), null));

        mockMvc.perform(get("/api/v1/instruments/VOO/prices").param("range", "5Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].date").value("2026-08-27"))
                .andExpect(jsonPath("$.data[0].close").value("505.00"))
                .andExpect(jsonPath("$.data[0].adjustedClose").value("504.50"))
                .andExpect(jsonPath("$.dataStatus").value("FRESH"))
                .andExpect(jsonPath("$.source").value("YAHOO"))
                .andExpect(jsonPath("$.asOf").value("2026-08-27"));
    }

    @Test
    void exposesFullHistorySyncWithTheExistingSyncResponseShape() throws Exception {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        when(marketDataService.getInstrument("VOO")).thenReturn(instrument);
        when(marketDataService.fullResync(instrument)).thenReturn(new SyncResponse(
                "VOO", 1, 0, FreshnessStatus.FRESH, Instant.parse("2026-08-27T20:02:00Z"), null));

        mockMvc.perform(post("/api/v1/instruments/VOO/sync/full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("VOO"))
                .andExpect(jsonPath("$.barsSaved").value(1))
                .andExpect(jsonPath("$.splitsSaved").value(0))
                .andExpect(jsonPath("$.status").value("FRESH"));
    }

    @Test
    void omitsMissingAdjustedMetricsWithoutTurningThemIntoZeroWithPartialStatus() throws Exception {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        when(marketDataService.getInstrument("VOO")).thenReturn(instrument);
        when(marketDataService.metrics(instrument)).thenReturn(new MetricsResponse(
                null, null, null, null, null, null, new java.math.BigDecimal("520"),
                new java.math.BigDecimal("480"), null, null, FreshnessStatus.PARTIAL,
                LocalDate.of(2026, 8, 27)));

        mockMvc.perform(get("/api/v1/instruments/VOO/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oneMonth").doesNotExist())
                .andExpect(jsonPath("$.currentDrawdown").doesNotExist())
                .andExpect(jsonPath("$.fiftyTwoWeekHigh").value("520"))
                .andExpect(jsonPath("$.dataStatus").value("PARTIAL"));
    }
}
