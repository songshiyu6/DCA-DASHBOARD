package com.dca.terminal.transaction;

import com.dca.terminal.config.JacksonConfig;
import com.dca.terminal.instrument.InstrumentEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class TransactionControllerJsonTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    @Test
    void serializesMaximumQuantityAndMoneyPrecisionAsExactJsonStrings() throws Exception {
        UUID transactionId = UUID.randomUUID();
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol("VOO");
        instrument.setName("Vanguard S&P 500 ETF");
        TransactionEntity transaction = new TransactionEntity();
        ReflectionTestUtils.setField(transaction, "id", transactionId);
        transaction.setInstrument(instrument);
        transaction.setTransactionType(TransactionType.BUY);
        transaction.setTradeDate(LocalDate.of(2026, 8, 31));
        transaction.setQuantity(new BigDecimal("999999999999.12345678"));
        transaction.setUnitPrice(new BigDecimal("99999999999999.123456"));
        transaction.setFee(BigDecimal.ZERO.setScale(6));
        when(transactionService.get(transactionId)).thenReturn(transaction);

        mockMvc.perform(get("/api/v1/transactions/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value("999999999999.12345678"))
                .andExpect(jsonPath("$.unitPrice").value("99999999999999.123456"));
    }
}
