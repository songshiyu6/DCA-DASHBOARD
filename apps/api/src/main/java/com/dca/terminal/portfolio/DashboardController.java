package com.dca.terminal.portfolio;

import com.dca.terminal.plan.PlanDtos.ContributionProgress;
import com.dca.terminal.plan.PlanDtos.NextDcaResponse;
import com.dca.terminal.plan.PlanDtos.PlanResponse;
import com.dca.terminal.plan.PlanService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.dca.terminal.portfolio.PortfolioDtos.DashboardResponse;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final PortfolioService portfolioService;
    private final PlanService planService;

    public DashboardController(PortfolioService portfolioService, PlanService planService) {
        this.portfolioService = portfolioService;
        this.planService = planService;
    }

    @GetMapping
    public DashboardResponse dashboard() {
        PlanResponse active = planService.list().stream()
                .filter(plan -> plan.status().name().equals("ACTIVE"))
                .findFirst().orElse(null);
        NextDcaResponse nextDca = active == null ? null : planService.nextDca(active.id()).orElse(null);
        ContributionProgress progress = active == null ? null : planService.contributionProgress(active.id());
        PortfolioService.CurrentViews current = portfolioService.currentViews();
        return new DashboardResponse(current.summary(), nextDca, portfolioService.history("ALL"),
                current.holdings(), current.allocation(), progress);
    }
}