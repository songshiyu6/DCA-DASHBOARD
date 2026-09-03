package com.dca.terminal.portfolio;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PortfolioDailySettlementSchedulerTest {
    @Test
    void runsAtMidnightInTheNewYorkTimeZone() throws Exception {
        Method method = PortfolioDailySettlementScheduler.class
                .getMethod("settlePortfolioAtEasternMidnight");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${dca.scheduler.settlement-cron:0 0 0 * * *}", scheduled.cron());
        assertEquals("America/New_York", scheduled.zone());
    }

    @Test
    void delegatesToTheSettlementService() {
        PortfolioDailySettlementService settlementService = mock(PortfolioDailySettlementService.class);

        new PortfolioDailySettlementScheduler(settlementService).settlePortfolioAtEasternMidnight();

        verify(settlementService).settleEasternMidnight();
    }
}
