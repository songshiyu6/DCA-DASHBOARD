package com.dca.terminal.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneId applicationZone(@Value("${dca.timezone:America/New_York}") String timezone) {
        return ZoneId.of(timezone);
    }
}
