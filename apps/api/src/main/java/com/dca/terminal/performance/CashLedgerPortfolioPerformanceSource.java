package com.dca.terminal.performance;

import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.transaction.CashLedgerCalculator;
import com.dca.terminal.transaction.TransactionRepository;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Cash-ledger-backed performance source.
 *
 * <p>Total value is the cash-inclusive portfolio value exposed by {@link PortfolioService}.
 * Cumulative external flow is net DEPOSIT minus WITHDRAWAL; BUY/SELL and all other account-internal
 * activity never enter the performance cash-flow stream.</p>
 */
@Component
public class CashLedgerPortfolioPerformanceSource implements PortfolioPerformanceSource {
    public static final String EXTERNAL_FLOW_MODEL = "CASH_LEDGER_DEPOSIT_WITHDRAWAL";

    private final PortfolioService portfolioService;
    private final TransactionRepository transactionRepository;
    private final ZoneId zone;

    public CashLedgerPortfolioPerformanceSource(PortfolioService portfolioService,
                                                TransactionRepository transactionRepository,
                                                ZoneId zone) {
        this.portfolioService = portfolioService;
        this.transactionRepository = transactionRepository;
        this.zone = zone;
    }

    @Override
    public List<DailyValuation> regularCloseHistory() {
        return portfolioService.history("ALL").stream()
                .map(point -> new DailyValuation(point.date(), point.marketValue(), point.netInvested(), point.status()))
                .toList();
    }

    @Override
    public CurrentValuation current() {
        var summary = portfolioService.summary();
        var asOf = summary.asOf();
        var businessDate = asOf == null ? null : asOf.atZone(zone).toLocalDate();
        return new CurrentValuation(businessDate, asOf, summary.marketValue(), summary.netInvested(), summary.status());
    }

    @Override
    public List<ExternalCashFlow> externalCashFlows() {
        return transactionRepository.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc().stream()
                .map(transaction -> new ExternalCashFlow(transaction.getTradeDate(),
                        CashLedgerCalculator.externalFlowChange(transaction)))
                .filter(flow -> flow.portfolioAmount() != null && flow.portfolioAmount().signum() != 0)
                .toList();
    }

    @Override
    public String externalFlowModel() {
        return EXTERNAL_FLOW_MODEL;
    }
}
