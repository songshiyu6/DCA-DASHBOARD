package com.dca.terminal.plan;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.config.JacksonConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ContributionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class ContributionControllerJsonTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContributionAnalysisService analysisService;

    @MockBean
    private ContributionClassificationService classificationService;

    @Test
    void serializesMaximumMoneyPrecisionAsAnExactJsonString() throws Exception {
        UUID planId = UUID.randomUUID();
        BigDecimal maximumMoney = new BigDecimal("99999999999999.123456");
        ContributionDtos.ContributionBucketResponse bucket = new ContributionDtos.ContributionBucketResponse(
                maximumMoney, maximumMoney, BigDecimal.ZERO.setScale(6), BigDecimal.ZERO,
                0, 1, FreshnessStatus.FRESH);
        when(analysisService.analyze(planId)).thenReturn(new ContributionDtos.ContributionAnalysisResponse(
                maximumMoney, bucket, new ContributionDtos.ContributionBucketResponse(
                BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6),
                null, 0, 0, FreshnessStatus.FRESH),
                BigDecimal.ZERO.setScale(6), List.of(), "ACCOUNT", List.of(), FreshnessStatus.FRESH,
                LocalDate.of(2026, 8, 31)));

        mockMvc.perform(get("/api/v1/plans/{planId}/contribution-analysis", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInvested").value("99999999999999.123456"))
                .andExpect(jsonPath("$.initial.principal").value("99999999999999.123456"));
    }
}
