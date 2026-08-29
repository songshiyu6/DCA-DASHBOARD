package com.dca.terminal.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class TimeConfig {
    public static final String MARKET_ZONE = "marketZone";
    public static final String USER_ZONE = "userZone";

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean(name = MARKET_ZONE)
    @Primary
    public ZoneId marketZone() {
        return ZoneId.of("America/New_York");
    }

    @Bean(name = USER_ZONE)
    public ZoneId userZone() {
        return ZoneId.of("Asia/Shanghai");
    }
}
