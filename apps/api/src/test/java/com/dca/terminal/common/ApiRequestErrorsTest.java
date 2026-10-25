package com.dca.terminal.common;

import com.dca.terminal.transaction.TransactionController;
import com.dca.terminal.transaction.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiRequestErrorsTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new TransactionController(mock(TransactionService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void malformedJsonIsAClientError() throws Exception {
        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedUuidIsAClientError() throws Exception {
        mvc.perform(get("/api/v1/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedFilterDateIsAClientError() throws Exception {
        mvc.perform(get("/api/v1/transactions").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodRetainsItsHttpStatus() throws Exception {
        mvc.perform(patch("/api/v1/transactions"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsupportedContentTypeRetainsItsHttpStatus() throws Exception {
        mvc.perform(post("/api/v1/transactions").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
