package com.dca.terminal.marketdata;

import com.dca.terminal.config.TimeConfig;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.portfolio.PortfolioService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataSchedulerTest {
    @Test
    void schedulesRegularCloseSnapshotsAfterMarketCloseInNewYork() throws Exception {
        Method method = MarketDataScheduler.class.getMethod("syncActiveInstruments");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${dca.scheduler.cron:0 30 18 * * MON-FRI}", scheduled.cron());
        assertEquals(TimeConfig.MARKET_ZONE_ID, scheduled.zone());
    }

    @Test
    void rebuildsPortfolioSnapshotAfterTheInstrumentBatchEvenWhenOneSyncFails() {
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getSymbol()).thenReturn("VOO");
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findAllByTrackedTrueOrderBySymbolAsc()).thenReturn(List.of(instrument));
        MarketDataService marketData = mock(MarketDataService.class);
        doThrow(new RuntimeException("provider unavailable")).when(marketData).sync(instrument);
        PortfolioService portfolio = mock(PortfolioService.class);

        new MarketDataScheduler(instruments, marketData, portfolio).syncActiveInstruments();

        verify(portfolio).rebuildTodaySnapshot();
    }
}
