package com.dca.terminal.marketdata;

import com.dca.terminal.config.TimeConfig;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.portfolio.PortfolioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dca.scheduler.enabled", havingValue = "true")
public class MarketDataScheduler {
    private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);
    private final InstrumentRepository instrumentRepository;
    private final MarketDataService marketDataService;
    private final PortfolioService portfolioService;

    public MarketDataScheduler(InstrumentRepository instrumentRepository, MarketDataService marketDataService,
                               PortfolioService portfolioService) {
        this.instrumentRepository = instrumentRepository;
        this.marketDataService = marketDataService;
        this.portfolioService = portfolioService;
    }

    @Scheduled(cron = "${dca.scheduler.cron:0 30 18 * * MON-FRI}", zone = TimeConfig.MARKET_ZONE_ID)
    public void syncActiveInstruments() {
        log.info("market sync started");
        instrumentRepository.findAllByTrackedTrueOrderBySymbolAsc().forEach(instrument -> {
            try {
                marketDataService.sync(instrument);
            } catch (Exception exception) {
                log.warn("market sync failed ticker={} reason={}", instrument.getSymbol(), exception.getMessage());
            }
        });
        try {
            // Historical account-value snapshots are regular-close marks for the New York trading date.
            portfolioService.rebuildTodaySnapshot();
        } catch (Exception exception) {
            log.warn("portfolio snapshot rebuild failed reason={}", exception.getMessage());
        }
        log.info("market sync completed");
    }
}
