package com.dca.terminal.portfolio;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioDailySettlementRepository extends JpaRepository<PortfolioDailySettlementEntity, UUID> {
    Optional<PortfolioDailySettlementEntity> findBySettlementDate(LocalDate settlementDate);
}
