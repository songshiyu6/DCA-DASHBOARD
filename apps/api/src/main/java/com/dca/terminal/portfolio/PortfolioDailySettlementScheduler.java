package com.dca.terminal.portfolio;

import com.dca.terminal.config.TimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dca.scheduler.enabled", havingValue = "true")
public class PortfolioDailySettlementScheduler {
    private static final Logger log = LoggerFactory.getLogger(PortfolioDailySettlementScheduler.class);
    private final PortfolioDailySettlementService settlementService;

    public PortfolioDailySettlementScheduler(PortfolioDailySettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(cron = "${dca.scheduler.settlement-cron:0 0 0 * * *}", zone = TimeConfig.MARKET_ZONE_ID)
    public void settlePortfolioAtEasternMidnight() {
        try {
            settlementService.settleEasternMidnight();
            log.info("portfolio daily settlement completed zone={} boundary=00:00", TimeConfig.MARKET_ZONE_ID);
        } catch (Exception exception) {
            log.warn("portfolio daily settlement failed zone={} reason={}",
                    TimeConfig.MARKET_ZONE_ID, exception.getMessage());
        }
    }
}
