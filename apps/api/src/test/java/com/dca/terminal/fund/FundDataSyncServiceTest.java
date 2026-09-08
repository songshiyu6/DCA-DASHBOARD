package com.dca.terminal.fund;

import com.dca.terminal.common.DomainException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        FundDtos.FundResponse fund = createFund("000001");
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
        FundDataSyncService service = service(provider);

        var result = service.sync(fundId, LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-08"));

        assertThat(result.missingNavDates()).containsExactly(LocalDate.parse("2026-09-08"));
        assertThat(navRepository.findAll()).hasSize(1);
        assertThat(calendarRepository.findAll()).hasSize(2);
    }

    @Test
    void providerFailurePreservesExistingNavAndCalendarFacts() {
        FundDtos.FundResponse fund = createFund("000002");
        UUID fundId = fund.id();
        fundService.putNav(fundId, new FundDtos.NavRequest(
                LocalDate.parse("2026-09-05"), new BigDecimal("1.11"), "MANUAL"));

        FundMarketCalendarDayEntity existingDay = new FundMarketCalendarDayEntity();
        existingDay.setCalendarCode("CN_FUND");
        existingDay.setMarketDate(LocalDate.parse("2026-09-05"));
        existingDay.setSource("TEST");
        existingDay.setRetrievedAt(Instant.parse("2026-09-05T12:00:00Z"));
        calendarRepository.saveAndFlush(existingDay);

        ChinaFundDataProvider provider = new ChinaFundDataProvider() {
            @Override public String source() { return "TEST"; }
            @Override public List<NavPoint> navHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
                throw new FundDataProviderException("upstream unavailable");
            }
            @Override public List<LocalDate> tradingDays(LocalDate startDate, LocalDate endDate) {
                return List.of();
            }
        };

        assertThatThrownBy(() -> service(provider).sync(
                fundId, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-08")))
                .isInstanceOf(DomainException.class);
        assertThat(navRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getNavDate()).isEqualTo(LocalDate.parse("2026-09-05"));
            assertThat(row.getNav()).isEqualByComparingTo("1.11");
            assertThat(row.getSource()).isEqualTo("MANUAL");
        });
        assertThat(calendarRepository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getMarketDate()).isEqualTo(LocalDate.parse("2026-09-05")));
    }

    private FundDtos.FundResponse createFund(String code) {
        return fundService.create(new FundDtos.FundRequest(code, "Test Fund", BigDecimal.ZERO, 1, null));
    }

    private FundDataSyncService service(ChinaFundDataProvider provider) {
        return new FundDataSyncService(provider, fundService, calendarRepository, navRepository,
                Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC));
    }
}
