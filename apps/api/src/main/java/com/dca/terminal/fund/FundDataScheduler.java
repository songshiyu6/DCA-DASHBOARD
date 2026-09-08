package com.dca.terminal.fund;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dca.fund-data.scheduler.enabled", havingValue = "true")
public class FundDataScheduler {
    private static final Logger log = LoggerFactory.getLogger(FundDataScheduler.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int REFRESH_LOOKBACK_DAYS = 14;

    private final FundProfileRepository profileRepository;
    private final FundDataSyncService syncService;
    private final Clock clock;

    public FundDataScheduler(FundProfileRepository profileRepository, FundDataSyncService syncService, Clock clock) {
        this.profileRepository = profileRepository;
        this.syncService = syncService;
        this.clock = clock;
    }

    @Scheduled(cron = "${dca.fund-data.scheduler.cron:0 30 22 * * MON-FRI}", zone = "Asia/Shanghai")
    public void syncFunds() {
        LocalDate endDate = LocalDate.now(clock.withZone(CHINA_ZONE));
        LocalDate startDate = endDate.minusDays(REFRESH_LOOKBACK_DAYS);
        log.info("China fund sync started range={}..{}", startDate, endDate);
        profileRepository.findAll().forEach(profile -> {
            try {
                syncService.sync(profile.getInstrument().getId(), startDate, endDate);
            } catch (Exception exception) {
                log.warn("China fund sync failed code={} reason={}",
                        profile.getInstrument().getSymbol(), exception.getMessage());
            }
        });
        log.info("China fund sync completed");
    }
}
