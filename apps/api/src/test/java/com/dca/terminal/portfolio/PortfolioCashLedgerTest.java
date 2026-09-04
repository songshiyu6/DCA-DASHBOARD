package com.dca.terminal.portfolio;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioCashLedgerTest {
    @Test
    void totalPortfolioValueIncludesCashWhileNetInvestedOnlyUsesExternalFlows() {
        UUID instrumentId = UUID.randomUUID();
        InstrumentEntity instrument = mock(InstrumentEntity.class);
        when(instrument.getId()).thenReturn(instrumentId);
        when(instrument.getSymbol()).thenReturn("VOO");
        when(instrument.getName()).thenReturn("Vanguard S&P 500 ETF");

        LocalDate today = LocalDate.of(2026, 9, 4);
        TransactionEntity deposit = amount(TransactionType.DEPOSIT, LocalDate.of(2026, 9, 1), "1000", 1L, null);
        TransactionEntity buy = new TransactionEntity();
        buy.setInstrument(instrument);
        buy.setTransactionType(TransactionType.BUY);
        buy.setTradeDate(LocalDate.of(2026, 9, 1));
        buy.setQuantity(new BigDecimal("2"));
        buy.setUnitPrice(new BigDecimal("300"));
        buy.setFee(BigDecimal.ZERO);
        buy.setLedgerOrder(2L);
        TransactionEntity dividend = amount(TransactionType.DIVIDEND, LocalDate.of(2026, 9, 3), "20", 3L, instrument);

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findAllByTradeDateLessThanEqualOrderByTradeDateAscLedgerOrderAscIdAsc(today))
                .thenReturn(List.of(deposit, buy, dividend));
        SplitEventRepository splits = mock(SplitEventRepository.class);
        when(splits.findAllByInstrumentIdInAndEffectiveDateLessThanEqualOrderByInstrumentIdAscEffectiveDateAsc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of());
        PriceDailyRepository prices = mock(PriceDailyRepository.class);
        PriceDailyEntity daily = new PriceDailyEntity();
        daily.setInstrument(instrument);
        daily.setTradeDate(today);
        daily.setClose(new BigDecimal("320"));
        when(prices.findAllByInstrumentIdInAndTradeDateLessThanEqualOrderByInstrumentIdAscTradeDateDesc(
                anyCollection(), any(LocalDate.class))).thenReturn(List.of(daily));
        QuoteLatestRepository quotes = mock(QuoteLatestRepository.class);
        when(quotes.findAllByInstrumentIdIn(anyCollection())).thenReturn(List.of());
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findAllById(anyCollection())).thenReturn(List.of(instrument));
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.providerPriority()).thenReturn(List.of(ProviderId.YAHOO, ProviderId.TWELVE_DATA));

        PortfolioService service = new PortfolioService(transactions, instruments, prices, quotes, splits,
                mock(PortfolioSnapshotRepository.class), mock(PlanRepository.class), mock(AssetRepository.class),
                marketData, Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);

        PortfolioDtos.SummaryResponse summary = service.summary();

        assertDecimal("640", summary.securitiesValue());
        assertDecimal("420", summary.cashBalance());
        assertDecimal("1060", summary.marketValue());
        assertDecimal("1000", summary.netInvested());
        assertDecimal("40", summary.unrealizedPnl());
        assertDecimal("20", summary.dividendIncome());
        assertDecimal("60", summary.totalPnl());
        assertEquals(0, summary.interestIncome().signum());
    }

    private static TransactionEntity amount(TransactionType type, LocalDate date, String amount,
                                            long order, InstrumentEntity instrument) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setInstrument(instrument);
        transaction.setTransactionType(type);
        transaction.setTradeDate(date);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setFee(BigDecimal.ZERO);
        transaction.setLedgerOrder(order);
        return transaction;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
