package com.dca.terminal.plan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<InvestmentPlanAssetEntity, UUID> {
    List<InvestmentPlanAssetEntity> findAllByPlanIdOrderByIdAsc(UUID planId);
    void deleteAllByPlanId(UUID planId);
}
