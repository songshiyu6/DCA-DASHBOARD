package com.dca.terminal.portfolio;

import com.dca.terminal.common.FreshnessStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.portfolio.PortfolioDtos.DailySettlementResponse;

@Service
public class PortfolioDailySettlementService {
    private final PortfolioDailySettlementRepository settlementRepository;
    private final PortfolioService portfolioService;
    private final Clock clock;
    private final ZoneId zone;

    public PortfolioDailySettlementService(PortfolioDailySettlementRepository settlementRepository,
                                           PortfolioService portfolioService,
                                           Clock clock,
                                           ZoneId zone) {
        this.settlementRepository = settlementRepository;
        this.portfolioService = portfolioService;
        this.clock = clock;
        this.zone = zone;
    }

    @Transactional(readOnly = true)
    public DailySettlementResponse current() {
        return settlementRepository.findBySettlementDate(today()).map(this::toResponse).orElse(null);
    }

    /**
     * Freezes the opening mark for the New York calendar day. The scheduler invokes this at 00:00
     * America/New_York, so the stored settlement is the baseline for that day's P/L. Existing rows
     * are immutable: a delayed retry must not silently move the accounting boundary later in the day.
     */
    @Transactional
    public DailySettlementResponse settleEasternMidnight() {
        LocalDate settlementDate = today();
        return settlementRepository.findBySettlementDate(settlementDate)
                .map(this::toResponse)
                .orElseGet(() -> createSettlement(settlementDate));
    }

    private DailySettlementResponse createSettlement(LocalDate settlementDate) {
        PortfolioDtos.SummaryResponse summary = portfolioService.currentViews().summary();
        PortfolioDailySettlementEntity entity = new PortfolioDailySettlementEntity();
        entity.setSettlementDate(settlementDate);
        entity.setSettlementAt(settlementDate.atStartOfDay(zone).toInstant());
        entity.setNetCashFlow(summary.netInvested());
        entity.setDataStatus(summary.status());
        entity.setMarketValue(summary.status() == FreshnessStatus.FRESH ? summary.marketValue() : null);
        return toResponse(settlementRepository.save(entity));
    }

    private DailySettlementResponse toResponse(PortfolioDailySettlementEntity entity) {
        return new DailySettlementResponse(entity.getSettlementDate(), entity.getSettlementAt(),
                entity.getMarketValue(), entity.getNetCashFlow(), entity.getDataStatus());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
