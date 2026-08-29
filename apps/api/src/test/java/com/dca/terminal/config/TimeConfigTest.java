package com.dca.terminal.config;

import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeConfigTest {
    @Test
    void keepsMarketBusinessDatesOnNewYorkTime() {
        TimeConfig config = new TimeConfig();

        assertEquals(ZoneOffset.UTC, config.applicationClock().getZone());
        assertEquals(ZoneId.of("America/New_York"), config.applicationZone());
    }
}
