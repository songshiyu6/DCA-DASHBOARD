package com.dca.terminal.fx;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dca.fx.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class FxScheduler {
    private static final Logger log = LoggerFactory.getLogger(FxScheduler.class);
    private final FxService service;
    private final Clock clock;

    public FxScheduler(FxService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${dca.fx.scheduler.cron:0 15 7 * * TUE-SAT}", zone = "America/New_York")
    public void refreshRecentUsdCny() {
        LocalDate end = LocalDate.now(clock);
        try {
            FxDtos.FxSyncResponse result = service.syncUsdCny(end.minusDays(14), end);
            log.info("USD/CNY FX refresh completed persistedRows={} latestRateDate={}",
                    result.persistedRows(), result.latestRateDate());
        } catch (RuntimeException exception) {
            log.warn("USD/CNY FX refresh failed reason={}", exception.getMessage());
        }
    }
}
