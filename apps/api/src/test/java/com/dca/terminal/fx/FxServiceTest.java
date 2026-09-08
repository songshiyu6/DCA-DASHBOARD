package com.dca.terminal.fx;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.YahooFinanceProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxServiceTest {
    private static final LocalDate START = LocalDate.of(2026, 9, 7);
    private static final LocalDate END = LocalDate.of(2026, 9, 8);
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    @Mock FxRateRepository repository;
    @Mock YahooFinanceProvider yahoo;

    private FxService service;

    @BeforeEach
    void setUp() {
        service = new FxService(repository, yahoo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsYahooUsdCnyCloseWithExplicitPairSemantics() {
        when(yahoo.getHistoricalPrices(any(InstrumentEntity.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new PriceBar(START, null, null, null, new BigDecimal("7.1000"), null, null)));
        when(repository.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateAndSource(
                "USD", "CNY", START, FxService.USD_CNY_SOURCE)).thenReturn(Optional.empty());
        FxRateEntity latest = rate(START, "7.1000");
        when(repository.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDescRetrievedAtDesc(
                "USD", "CNY", END)).thenReturn(Optional.of(latest));

        FxDtos.FxSyncResponse result = service.syncUsdCny(START, END);

        ArgumentCaptor<FxRateEntity> saved = ArgumentCaptor.forClass(FxRateEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getBaseCurrency()).isEqualTo("USD");
        assertThat(saved.getValue().getQuoteCurrency()).isEqualTo("CNY");
        assertThat(saved.getValue().getRateDate()).isEqualTo(START);
        assertThat(saved.getValue().getRate()).isEqualByComparingTo("7.1000");
        assertThat(saved.getValue().getSource()).isEqualTo("YAHOO:CNY=X");
        assertThat(saved.getValue().getRetrievedAt()).isEqualTo(NOW);
        assertThat(result.latestRate()).isEqualByComparingTo("7.1000");
        assertThat(result.persistedRows()).isEqualTo(1);
    }

    @Test
    void recentProviderEmptyFailsWithoutWritingFinancialFacts() {
        when(yahoo.getHistoricalPrices(any(InstrumentEntity.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.syncUsdCny(START, END))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("no usable USD/CNY rates");

        verify(repository, never()).save(any());
        verify(repository, never()).flush();
    }

    @Test
    void cnyToUsdUsesLatestStoredRateOnOrBeforeTheValuationDate() {
        when(repository.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDescRetrievedAtDesc(
                "USD", "CNY", END)).thenReturn(Optional.of(rate(START, "7.10")));

        assertThat(service.cnyToUsd(new BigDecimal("710"), END)).isEqualByComparingTo("100");
    }

    private static FxRateEntity rate(LocalDate date, String value) {
        FxRateEntity entity = new FxRateEntity();
        entity.setBaseCurrency("USD");
        entity.setQuoteCurrency("CNY");
        entity.setRateDate(date);
        entity.setRate(new BigDecimal(value));
        entity.setSource(FxService.USD_CNY_SOURCE);
        entity.setRetrievedAt(NOW);
        return entity;
    }
}
