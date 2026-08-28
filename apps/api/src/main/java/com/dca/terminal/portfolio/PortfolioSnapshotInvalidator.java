package com.dca.terminal.portfolio;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioSnapshotInvalidator {
    private final PortfolioSnapshotRepository snapshotRepository;

    public PortfolioSnapshotInvalidator(PortfolioSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional
    public void invalidateFrom(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Snapshot invalidation date is required");
        snapshotRepository.deleteAllBySnapshotDateGreaterThanEqual(date);
    }

    @Transactional
    public void invalidateAll() {
        snapshotRepository.deleteAllInBatch();
    }
}
