package com.dca.terminal.performance;

import com.dca.terminal.portfolio.PortfolioDtos.HistoryPoint;
import com.dca.terminal.portfolio.PortfolioDtos.SummaryResponse;
import com.dca.terminal.portfolio.PortfolioService;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Transitional adapter for the pre-cash-ledger portfolio model.
 *
 * <p>PR A should replace this adapter with a cash-inclusive implementation where totalValue includes securities
 * plus cash and cumulativeExternalFlow is DEPOSIT minus WITHDRAWAL. Until then, the existing netInvested value is
 * used only as the TWR flow proxy. XIRR is deliberately disabled because the legacy transaction model represents
 * BUY/SELL as investor cash flows.</p>
 */
@Component
public class LegacyPortfolioPerformanceSource implements PortfolioPerformanceSource {
    public static final String FLOW_MODEL = "LEGACY_NET_INVESTED_PROXY_XIRR_DISABLED";
    private static final ZoneId PORTFOLIO_ZONE = ZoneId.of("America/New_York");

    private final PortfolioService portfolioService;

    public LegacyPortfolioPerformanceSource(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @Override
    public List<DailyValuation> regularCloseHistory() {
        return portfolioService.history("ALL").stream().map(this::dailyValuation).toList();
    }

    @Override
    public CurrentValuation current() {
        SummaryResponse summary = portfolioService.summary();
        if (summary == null || summary.asOf() == null) return null;
        return new CurrentValuation(summary.asOf().atZone(PORTFOLIO_ZONE).toLocalDate(), summary.asOf(),
                summary.marketValue(), summary.netInvested(), summary.status());
    }

    @Override
    public List<ExternalCashFlow> externalCashFlows() {
        return List.of();
    }

    @Override
    public String externalFlowModel() {
        return FLOW_MODEL;
    }

    private DailyValuation dailyValuation(HistoryPoint point) {
        return new DailyValuation(point.date(), point.marketValue(), point.netInvested(), point.status());
    }
}
