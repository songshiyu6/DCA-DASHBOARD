package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioDailySettlementServiceTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void freezesTheSummerBoundaryAtMidnightNewYork() {
        assertSettlementBoundary("2026-07-06T04:00:05Z", LocalDate.of(2026, 7, 6),
                Instant.parse("2026-07-06T04:00:00Z"));
    }

    @Test
    void freezesTheWinterBoundaryAtMidnightNewYork() {
        assertSettlementBoundary("2026-01-05T05:00:05Z", LocalDate.of(2026, 1, 5),
                Instant.parse("2026-01-05T05:00:00Z"));
    }

    @Test
    void doesNotExposeAnIncompleteValuationAsTheDailyOpeningMark() {
        PortfolioDailySettlementRepository repository = mock(PortfolioDailySettlementRepository.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(repository.findBySettlementDate(LocalDate.of(2026, 7, 6))).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(portfolio.currentViews()).thenReturn(new PortfolioService.CurrentViews(
                summary("1000", "900", FreshnessStatus.PARTIAL), List.of(), List.of()));

        PortfolioDailySettlementService service = new PortfolioDailySettlementService(repository, portfolio,
                Clock.fixed(Instant.parse("2026-07-06T04:00:05Z"), ZoneOffset.UTC), NEW_YORK);

        PortfolioDtos.DailySettlementResponse settlement = service.settleEasternMidnight();

        assertNull(settlement.marketValue());
        assertEquals(FreshnessStatus.PARTIAL, settlement.status());
    }

    @Test
    void keepsAnExistingSettlementImmutableOnRetry() {
        PortfolioDailySettlementRepository repository = mock(PortfolioDailySettlementRepository.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        PortfolioDailySettlementEntity existing = new PortfolioDailySettlementEntity();
        existing.setSettlementDate(LocalDate.of(2026, 7, 6));
        existing.setSettlementAt(Instant.parse("2026-07-06T04:00:00Z"));
        existing.setMarketValue(new BigDecimal("1000"));
        existing.setNetCashFlow(new BigDecimal("900"));
        existing.setDataStatus(FreshnessStatus.FRESH);
        when(repository.findBySettlementDate(existing.getSettlementDate())).thenReturn(Optional.of(existing));

        PortfolioDailySettlementService service = new PortfolioDailySettlementService(repository, portfolio,
                Clock.fixed(Instant.parse("2026-07-06T16:00:00Z"), ZoneOffset.UTC), NEW_YORK);

        PortfolioDtos.DailySettlementResponse settlement = service.settleEasternMidnight();

        assertEquals(Instant.parse("2026-07-06T04:00:00Z"), settlement.settledAt());
        verify(portfolio, never()).currentViews();
        verify(repository, never()).save(any());
    }

    private static void assertSettlementBoundary(String now, LocalDate expectedDate, Instant expectedBoundary) {
        PortfolioDailySettlementRepository repository = mock(PortfolioDailySettlementRepository.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(repository.findBySettlementDate(expectedDate)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(portfolio.currentViews()).thenReturn(new PortfolioService.CurrentViews(
                summary("1000", "900", FreshnessStatus.FRESH), List.of(), List.of()));

        PortfolioDailySettlementService service = new PortfolioDailySettlementService(repository, portfolio,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC), NEW_YORK);
        service.settleEasternMidnight();

        ArgumentCaptor<PortfolioDailySettlementEntity> captor = ArgumentCaptor.forClass(PortfolioDailySettlementEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(expectedDate, captor.getValue().getSettlementDate());
        assertEquals(expectedBoundary, captor.getValue().getSettlementAt());
        assertEquals(new BigDecimal("1000"), captor.getValue().getMarketValue());
        assertEquals(new BigDecimal("900"), captor.getValue().getNetCashFlow());
    }

    private static PortfolioDtos.SummaryResponse summary(String marketValue, String netInvested, FreshnessStatus status) {
        return new PortfolioDtos.SummaryResponse(new BigDecimal(marketValue), BigDecimal.ZERO,
                new BigDecimal(netInvested), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, status, Instant.EPOCH);
    }
}
