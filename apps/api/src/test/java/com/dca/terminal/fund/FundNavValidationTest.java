package com.dca.terminal.fund;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class FundNavValidationTest {
    private final UUID id = UUID.randomUUID();
    private final FundNavDailyRepository nav = mock(FundNavDailyRepository.class);

    private FundService service() {
        var profiles = mock(FundProfileRepository.class);
        var profile = new FundProfileEntity();
        profile.setInstrument(new InstrumentEntity());
        when(profiles.findById(id)).thenReturn(Optional.of(profile));
        return new FundService(mock(InstrumentRepository.class), profiles, nav,
                mock(AutoDcaRuleRepository.class), mock(FundPurchaseRepository.class),
                Clock.fixed(Instant.parse("2026-10-24T17:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsFutureNavBeforeItCanAffectPerformance() {
        assertThatThrownBy(() -> service().putNav(id, new FundDtos.NavRequest(
                LocalDate.parse("2026-10-26"), BigDecimal.ONE, "MANUAL")))
                .isInstanceOf(DomainException.class).hasMessageContaining("future");
        verify(nav, never()).saveAndFlush(any());
    }

    @Test
    void acceptsCurrentShanghaiDateEvenBeforeUtcMidnight() {
        when(nav.saveAndFlush(any(FundNavDailyEntity.class))).thenAnswer(call -> call.getArgument(0));
        assertThat(service().putNav(id, new FundDtos.NavRequest(
                LocalDate.parse("2026-10-25"), BigDecimal.ONE, "MANUAL")).navDate())
                .isEqualTo(LocalDate.parse("2026-10-25"));
    }
}
