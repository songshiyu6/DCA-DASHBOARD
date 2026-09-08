package com.dca.terminal.fund;

import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FundDataSyncServiceTest {
    @Autowired InstrumentRepository instrumentRepository;
    @Autowired FundProfileRepository profileRepository;
    @Autowired FundMarketCalendarDayRepository calendarRepository;
    @Autowired FundNavDailyRepository navRepository;
    @Autowired FundService fundService;

    @BeforeEach
    void clean() {
        calendarRepository.deleteAll();
        navRepository.deleteAll();
        profileRepository.deleteAll();
        instrumentRepository.deleteAll();
    }

    @Test
    void reportsOpenDayWithoutNavAsGapAndDoesNotInventNav() {
        FundDtos.FundResponse fund = fundService.create(new FundDtos.FundRequest(
                "000001", "Test Fund", BigDecimal.ZERO, 1, null));
        UUID fundId = fund.id();

        ChinaFundDataProvider provider = new ChinaFundDataProvider() {
            @Override public String source() { return "TEST"; }
            @Override public List<NavPoint> navHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
                return List.of(new NavPoint(LocalDate.parse("2026-09-07"), new BigDecimal("1.23")));
            }
            @Override public List<LocalDate> tradingDays(LocalDate startDate, LocalDate endDate) {
                return List.of(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-08"));
            }
        };
        FundDataSyncService service = new FundDataSyncService(provider, fundService, calendarRepository, navRepository,
                Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC));

        var result = service.sync(fundId, LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-08"));

        assertThat(result.missingNavDates()).containsExactly(LocalDate.parse("2026-09-08"));
        assertThat(navRepository.findAll()).hasSize(1);
        assertThat(calendarRepository.findAll()).hasSize(2);
    }
}
