package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import com.dca.terminal.instrument.InstrumentDtos.QuoteResponse;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioLiveValuationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 31);
    private static final Instant NOW = Instant.parse("2026-08-31T09:45:00Z");

    @Test
    void currentPortfolioUsesRefreshedLiveQuoteInsteadOfRegularClose() {
        Fixture fixture = fixture();
        when(fixture.marketData.latestQuote(fixture.instrument)).thenReturn(new QuoteResponse(
                "VOO", new BigDecimal("125"), new BigDecimal("110"), new BigDecimal("15"),
                new BigDecimal("0.13636364"), null, null, NOW, NOW, "YAHOO",
                FreshnessStatus.FRESH, null, null));

        PortfolioService.CurrentViews views = fixture.service.currentViews();
        PortfolioDtos.SummaryResponse summary = views.summary();
        PortfolioDtos.HoldingResponse holding = views.holdings().getFirst();

        assertDecimal("125", summary.marketValue());
        assertDecimal("125", summary.securitiesValue());
        assertDecimal("0", summary.cashBalance());
        assertDecimal("100", summary.netInvested());
        assertDecimal("25", summary.unrealizedPnl());
        assertDecimal("25", summary.totalPnl());
        assertDecimal("125", holding.price());
        assertDecimal("125", holding.marketValue());
        assertDecimal("25", holding.unrealizedPnl());
        verify(fixture.marketData).latestQuote(fixture.instrument);
    }

    @Test
    void dailySnapshotUsesRegularCloseAndNeverLiveExtendedQuote() {
        Fixture fixture = fixture();
        when(fixture.snapshots.findBySnapshotDate(TODAY)).thenReturn(Optional.empty());

        fixture.service.rebuildTodaySnapshot();

        ArgumentCaptor<PortfolioSnapshotEntity> saved = ArgumentCaptor.forClass(PortfolioSnapshotEntity.class);
        verify(fixture.snapshots).save(saved.capture());
        PortfolioSnapshotEntity snapshot = saved.getValue();
        assertDecimal("110", snapshot.getMarketValue());
        assertDecimal("110", snapshot.getSecuritiesValue());
        assertDecimal("0", snapshot.getCashBalance());
        assertDecimal("10", snapshot.getUnrealizedPnl());
        verify(fixture.marketData, never()).latestQuote(any());
    }

    private static Fixture fixture() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

        TransactionEntity funding = new TransactionEntity();
        funding.setTransactionType(TransactionType.DEPOSIT);
        funding.setTradeDate(LocalDate.of(2026, 8, 1));
        funding.setAmount(new BigDecimal("100"));
        funding.setFee(BigDecimal.ZERO);
        funding.setLedgerOrder(1L);

        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(LocalDate.of(2026, 8, 1));
        buy.setQuantity(BigDecimal.ONE);
        buy.setUnitPrice(new BigDecimal("100"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(2L);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(any(LocalDate.class)))
                .thenReturn(List.of(funding, buy));

        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());

        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        PriceDailyEntity close = new PriceDailyEntity();
        close.setInstrument(instrument);
        close.setTradeDate(TODAY);
        close.setClose(new BigDecimal("110"));
        close.setAdjustedClose(new BigDecimal("110"));
        close.setSource("YAHOO");
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(close));

        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findAllByInstrumentIdIn(anyCollection())).thenReturn(List.of());

        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findAllById(any())).thenReturn(List.of(instrument));

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        PortfolioService service = new PortfolioService(transactions, instruments, prices, quotes, splits, snapshots,
                mock(PlanRepository.class), mock(AssetRepository.class), marketData,
                Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);
        return new Fixture(instrument, marketData, snapshots, service);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record Fixture(InstrumentEntity instrument, MarketDataService marketData,
                           PortfolioSnapshotRepository snapshots, PortfolioService service) { }
}
