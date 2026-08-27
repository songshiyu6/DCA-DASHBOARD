package com.dca.terminal.portfolio;

import com.dca.terminal.portfolio.PortfolioDtos.HistoryPoint;
import com.dca.terminal.portfolio.PortfolioDtos.HoldingResponse;
import com.dca.terminal.portfolio.PortfolioDtos.SummaryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {
    private final PortfolioService service;

    public PortfolioController(PortfolioService service) { this.service = service; }

    @GetMapping("/summary")
    public SummaryResponse summary() { return service.summary(); }

    @GetMapping("/holdings")
    public List<HoldingResponse> holdings() { return service.holdings(); }

    @GetMapping("/allocation")
    public List<PortfolioDtos.AllocationResponse> allocation() { return service.allocation(); }

    @GetMapping("/history")
    public List<HistoryPoint> history(@RequestParam(defaultValue = "1Y") String range) { return service.history(range); }

    @PostMapping("/rebuild-snapshot")
    public void rebuildSnapshot() { service.rebuildTodaySnapshot(); }
}
