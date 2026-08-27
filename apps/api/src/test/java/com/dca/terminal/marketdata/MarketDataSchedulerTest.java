package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.portfolio.PortfolioService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataSchedulerTest {
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
