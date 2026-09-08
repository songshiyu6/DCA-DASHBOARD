package com.dca.terminal.fund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EastMoneyFundDataProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesUnitNavAndSkipsInvalidProviderRows() throws Exception {
        JsonNode root = mapper.readTree("""
                {
                  "TotalCount": 3,
                  "Data": {"LSJZList": [
                    {"FSRQ": "2026-09-04", "DWJZ": "1.2345"},
                    {"FSRQ": "2026-09-07", "DWJZ": ""},
                    {"FSRQ": "2026-09-08", "DWJZ": "1.2500"}
                  ]}
                }
                """);

        assertThat(EastMoneyFundDataProvider.parseNavPage(root))
                .containsExactly(
                        new ChinaFundDataProvider.NavPoint(LocalDate.of(2026, 9, 4), new BigDecimal("1.2345")),
                        new ChinaFundDataProvider.NavPoint(LocalDate.of(2026, 9, 8), new BigDecimal("1.2500")));
    }

    @Test
    void parsesExchangeOpenDatesFromDailyKlines() throws Exception {
        JsonNode root = mapper.readTree("""
                {
                  "data": {"klines": [
                    "2026-09-04,3810.00,3812.00,3820.00,3790.00,100",
                    "2026-09-07,3812.00,3821.00,3825.00,3801.00,100",
                    "2026-09-08,3821.00,3830.00,3836.00,3810.00,100"
                  ]}
                }
                """);

        assertThat(EastMoneyFundDataProvider.parseTradingDays(root))
                .containsExactly(
                        LocalDate.of(2026, 9, 4),
                        LocalDate.of(2026, 9, 7),
                        LocalDate.of(2026, 9, 8));
    }
}
