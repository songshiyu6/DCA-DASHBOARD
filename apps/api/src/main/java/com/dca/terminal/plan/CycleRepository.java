package com.dca.terminal.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CycleRepository extends JpaRepository<InvestmentPlanCycleEntity, UUID> {
    List<InvestmentPlanCycleEntity> findAllByPlanIdOrderByPeriodAsc(UUID planId);
    Optional<InvestmentPlanCycleEntity> findByPlanIdAndPeriod(UUID planId, String period);
}
