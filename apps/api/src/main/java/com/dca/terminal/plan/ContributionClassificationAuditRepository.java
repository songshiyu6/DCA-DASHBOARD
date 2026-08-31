package com.dca.terminal.plan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContributionClassificationAuditRepository
        extends JpaRepository<ContributionClassificationAuditEntity, UUID> {
    List<ContributionClassificationAuditEntity> findTop100ByPlanIdOrderByCreatedAtDescIdDesc(UUID planId);
}
