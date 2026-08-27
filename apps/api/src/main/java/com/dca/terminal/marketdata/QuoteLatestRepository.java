package com.dca.terminal.marketdata;

import com.dca.terminal.marketdata.MarketDataEntities.QuoteLatestEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteLatestRepository extends JpaRepository<QuoteLatestEntity, UUID> {
    List<QuoteLatestEntity> findAllByInstrumentIdIn(Collection<UUID> instrumentIds);
}
