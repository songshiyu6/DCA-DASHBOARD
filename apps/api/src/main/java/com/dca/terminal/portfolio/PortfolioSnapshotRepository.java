package com.dca.terminal.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshotEntity, UUID> {
    List<PortfolioSnapshotEntity> findAllBySnapshotDateBetweenOrderBySnapshotDateAsc(LocalDate from, LocalDate to);
    Optional<PortfolioSnapshotEntity> findBySnapshotDate(LocalDate date);
}
