package com.dca.terminal.portfolio;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PortfolioSnapshotInvalidatorTest {
    @Test
    void deletesSnapshotsOnAndAfterTheChangedDate() {
        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        PortfolioSnapshotInvalidator invalidator = new PortfolioSnapshotInvalidator(snapshots);

        invalidator.invalidateFrom(LocalDate.of(2026, 8, 5));

        verify(snapshots).deleteAllBySnapshotDateGreaterThanEqual(LocalDate.of(2026, 8, 5));
    }

    @Test
    void canClearTheEntireRebuildableSnapshotCache() {
        PortfolioSnapshotRepository snapshots = mock(PortfolioSnapshotRepository.class);
        PortfolioSnapshotInvalidator invalidator = new PortfolioSnapshotInvalidator(snapshots);

        invalidator.invalidateAll();

        verify(snapshots).deleteAllInBatch();
    }
}
