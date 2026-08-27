package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.PriceDailyEntity;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.PriceDailyRepository;
import com.dca.terminal.marketdata.ProviderId;
import com.dca.terminal.marketdata.QuoteLatestRepository;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.plan.AssetRepository;
import com.dca.terminal.plan.PlanRepository;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionRepository;
import com.dca.terminal.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioServiceCorrectnessTest {

    @Test
    void doesNotReportMissingPriceAsAnUnrealizedLoss() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(LocalDate.of(2026, 8, 1));
        buy.setQuantity(new BigDecimal("1"));
        buy.setUnitPrice(new BigDecimal("100"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(1L);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(any(LocalDate.class)))
                .thenReturn(List.of(buy));
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        PriceDailyEntity invalidDaily = new PriceDailyEntity();
        invalidDaily.setInstrument(instrument);
        invalidDaily.setTradeDate(LocalDate.of(2026, 8, 27));
        invalidDaily.setClose(BigDecimal.ZERO);
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(invalidDaily));
        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findAllByInstrumentIdIn(anyCollection())).thenReturn(List.of());
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactions, mock(InstrumentRepository.class), prices, quotes,
                splits, mock(PortfolioSnapshotRepository.class), mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

        PortfolioDtos.SummaryResponse summary = service.summary();

        assertEquals(FreshnessStatus.PARTIAL, summary.status());
        assertEquals(0, summary.marketValue().signum());
        assertNull(summary.unrealizedPnl());
        assertNull(summary.totalPnl());
        assertNull(summary.xirr());

        PortfolioDtos.HoldingResponse holding = service.holdings().getFirst();
        assertNull(holding.price());
        assertNull(holding.marketValue());
        assertNull(holding.unrealizedPnl());
        assertNull(holding.returnPercent());
        assertNull(holding.allocation());
    }
}
