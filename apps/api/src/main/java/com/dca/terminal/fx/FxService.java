package com.dca.terminal.fx;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.ProviderModels.PriceBar;
import com.dca.terminal.marketdata.YahooFinanceProvider;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FxService {
    public static final String USD = "USD";
    public static final String CNY = "CNY";
    public static final String USD_CNY_SOURCE = "YAHOO:CNY=X";
    public static final String USD_CNY_SEMANTICS = "1 USD = rate CNY";
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final int MAX_SYNC_DAYS = 5_500;

    private final FxRateRepository repository;
    private final YahooFinanceProvider yahoo;
    private final Clock clock;

    public FxService(FxRateRepository repository, YahooFinanceProvider yahoo, Clock clock) {
        this.repository = repository;
        this.yahoo = yahoo;
        this.clock = clock;
    }

    @Transactional
    public FxDtos.FxSyncResponse syncUsdCny(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        InstrumentEntity fx = new InstrumentEntity();
        fx.setSymbol("CNY=X");
        fx.setName("USD/CNY");
        fx.setCurrency(CNY);
        fx.setTracked(false);

        List<PriceBar> fetched = yahoo.getHistoricalPrices(fx, startDate, endDate).stream()
                .filter(bar -> bar != null && bar.tradeDate() != null && bar.close() != null && bar.close().signum() > 0)
                .toList();
        if (fetched.isEmpty() && !endDate.isBefore(LocalDate.now(clock).minusDays(7))) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FX_PROVIDER_EMPTY",
                    "Yahoo returned no usable USD/CNY rates for the requested recent range");
        }

        for (PriceBar bar : fetched) {
            FxRateEntity entity = repository.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateAndSource(
                    USD, CNY, bar.tradeDate(), USD_CNY_SOURCE).orElseGet(FxRateEntity::new);
            entity.setBaseCurrency(USD);
            entity.setQuoteCurrency(CNY);
            entity.setRateDate(bar.tradeDate());
            entity.setRate(bar.close());
            entity.setSource(USD_CNY_SOURCE);
            entity.setRetrievedAt(clock.instant());
            repository.save(entity);
        }
        repository.flush();

        FxRateEntity latest = repository
                .findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDescRetrievedAtDesc(
                        USD, CNY, endDate)
                .orElse(null);
        return new FxDtos.FxSyncResponse(USD, CNY, startDate, endDate, fetched.size(),
                latest == null ? null : latest.getRateDate(), latest == null ? null : latest.getRate(), USD_CNY_SOURCE);
    }

    @Transactional(readOnly = true)
    public FxDtos.FxSeriesResponse usdCny(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        Map<LocalDate, FxRateEntity> selected = new LinkedHashMap<>();
        repository.findAllByBaseCurrencyAndQuoteCurrencyAndRateDateBetweenOrderByRateDateAscRetrievedAtDesc(
                        USD, CNY, startDate, endDate)
                .forEach(row -> selected.putIfAbsent(row.getRateDate(), row));
        return new FxDtos.FxSeriesResponse(USD, CNY, USD_CNY_SEMANTICS,
                selected.values().stream().map(this::response).toList());
    }

    @Transactional(readOnly = true)
    public FxRateEntity usdCnyOnOrBefore(LocalDate date) {
        return repository.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDescRetrievedAtDesc(
                        USD, CNY, date)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal cnyToUsd(BigDecimal cnyAmount, LocalDate date) {
        if (cnyAmount == null) return null;
        FxRateEntity rate = usdCnyOnOrBefore(date);
        if (rate == null || rate.getRate() == null || rate.getRate().signum() <= 0) return null;
        return cnyAmount.divide(rate.getRate(), MC);
    }

    private FxDtos.FxRateResponse response(FxRateEntity entity) {
        return new FxDtos.FxRateResponse(entity.getBaseCurrency(), entity.getQuoteCurrency(), entity.getRateDate(),
                entity.getRate(), entity.getSource(), entity.getRetrievedAt());
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FX_RANGE", "A valid FX date range is required");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) > MAX_SYNC_DAYS) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "FX_RANGE_TOO_LARGE",
                    "FX range exceeds the 5,500 day limit");
        }
    }
}
