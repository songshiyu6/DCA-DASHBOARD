package com.dca.terminal.fx;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRateEntity, UUID> {
    Optional<FxRateEntity> findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDescRetrievedAtDesc(
            String baseCurrency, String quoteCurrency, LocalDate rateDate);

    Optional<FxRateEntity> findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateAndSource(
            String baseCurrency, String quoteCurrency, LocalDate rateDate, String source);

    List<FxRateEntity> findAllByBaseCurrencyAndQuoteCurrencyAndRateDateBetweenOrderByRateDateAscRetrievedAtDesc(
            String baseCurrency, String quoteCurrency, LocalDate startDate, LocalDate endDate);
}
