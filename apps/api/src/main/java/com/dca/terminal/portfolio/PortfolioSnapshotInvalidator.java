package com.dca.terminal.portfolio;

import com.dca.terminal.observability.ObservabilityMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioSnapshotInvalidator {
    private final PortfolioSnapshotRepository snapshotRepository;
    private final MeterRegistry meterRegistry;

    public PortfolioSnapshotInvalidator(PortfolioSnapshotRepository snapshotRepository) {
        this(snapshotRepository, ObservabilityMetrics.noop());
    }

    @Autowired
    public PortfolioSnapshotInvalidator(PortfolioSnapshotRepository snapshotRepository, MeterRegistry meterRegistry) {
        this.snapshotRepository = snapshotRepository;
        this.meterRegistry = meterRegistry == null ? ObservabilityMetrics.noop() : meterRegistry;
    }

    @Transactional
    public void invalidateFrom(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Snapshot invalidation date is required");
        Timer.Sample sample = ObservabilityMetrics.start(meterRegistry);
        try {
            snapshotRepository.deleteAllBySnapshotDateGreaterThanEqual(date);
        } finally {
            ObservabilityMetrics.stop(meterRegistry, sample, ObservabilityMetrics.SNAPSHOT_INVALIDATE, "mode", "from");
        }
    }

    @Transactional
    public void invalidateAll() {
        Timer.Sample sample = ObservabilityMetrics.start(meterRegistry);
        try {
            snapshotRepository.deleteAllInBatch();
        } finally {
            ObservabilityMetrics.stop(meterRegistry, sample, ObservabilityMetrics.SNAPSHOT_INVALIDATE, "mode", "all");
        }
    }
}
