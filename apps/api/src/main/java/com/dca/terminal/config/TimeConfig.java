package com.dca.terminal.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {
    public static final String MARKET_ZONE_ID = "America/New_York";

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneId applicationZone() {
        return ZoneId.of(MARKET_ZONE_ID);
    }
}
