package com.dca.terminal.fund;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoDcaRuleRepository extends JpaRepository<AutoDcaRuleEntity, UUID> {
    List<AutoDcaRuleEntity> findAllByOrderByStartDateAscIdAsc();
    long deleteAllByInstrumentId(UUID instrumentId);
}
