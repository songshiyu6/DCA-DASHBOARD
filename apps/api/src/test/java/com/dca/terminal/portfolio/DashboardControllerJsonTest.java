package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.config.JacksonConfig;
import com.dca.terminal.plan.PlanService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class DashboardControllerJsonTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PortfolioService portfolioService;

    @MockBean
    private PlanService planService;

    @Test
    void returnsStableDashboardJsonStructureWhenPortfolioIsEmpty() throws Exception {
        Instant asOf = Instant.parse("2026-08-27T20:00:00Z");
        PortfolioDtos.SummaryResponse summary = new PortfolioDtos.SummaryResponse(
                bd("0.00"), bd("0.00"), bd("0.00"), bd("0.00"), bd("0.00"), bd("0.00"),
                bd("0.00"), bd("0.00"), null, FreshnessStatus.FRESH, asOf);
        when(portfolioService.currentViews()).thenReturn(new PortfolioService.CurrentViews(
                summary, List.of(), List.of()));
        when(portfolioService.history("ALL")).thenReturn(List.of());
        when(planService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.summary.marketValue").value("0.00"))
                .andExpect(jsonPath("$.summary.costBasis").value("0.00"))
                .andExpect(jsonPath("$.summary.netInvested").value("0.00"))
                .andExpect(jsonPath("$.summary.unrealizedPnl").value("0.00"))
                .andExpect(jsonPath("$.summary.realizedPnl").value("0.00"))
                .andExpect(jsonPath("$.summary.dividendIncome").value("0.00"))
                .andExpect(jsonPath("$.summary.totalFees").value("0.00"))
                .andExpect(jsonPath("$.summary.totalPnl").value("0.00"))
                .andExpect(jsonPath("$.summary.xirr").doesNotExist())
                .andExpect(jsonPath("$.summary.dataStatus").value("FRESH"))
                .andExpect(jsonPath("$.summary.asOf").value("2026-08-27T20:00:00Z"))
                .andExpect(jsonPath("$.dailySettlement").doesNotExist())
                .andExpect(jsonPath("$.nextDca").doesNotExist())
                .andExpect(jsonPath("$.portfolioHistory").isArray())
                .andExpect(jsonPath("$.portfolioHistory").isEmpty())
                .andExpect(jsonPath("$.holdings").isArray())
                .andExpect(jsonPath("$.holdings").isEmpty())
                .andExpect(jsonPath("$.allocation").isArray())
                .andExpect(jsonPath("$.allocation").isEmpty())
                .andExpect(jsonPath("$.contributionProgress").doesNotExist());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
