package com.dca.terminal.plan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CycleAssetRepository extends JpaRepository<InvestmentPlanCycleAssetEntity, UUID> {
    List<InvestmentPlanCycleAssetEntity> findAllByCycleIdOrderByIdAsc(UUID cycleId);
    void deleteAllByCycleId(UUID cycleId);
}
