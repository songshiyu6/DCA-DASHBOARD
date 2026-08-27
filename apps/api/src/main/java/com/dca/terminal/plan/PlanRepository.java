package com.dca.terminal.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<InvestmentPlanEntity, UUID> {
    List<InvestmentPlanEntity> findAllByOrderByCreatedAtAsc();
    Optional<InvestmentPlanEntity> findFirstByStatus(PlanStatus status);
}
