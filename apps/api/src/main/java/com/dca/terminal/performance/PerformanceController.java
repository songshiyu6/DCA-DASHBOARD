package com.dca.terminal.performance;

import com.dca.terminal.performance.PerformanceDtos.PortfolioPerformanceResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {
    private final PerformanceEngine performanceEngine;

    public PerformanceController(PerformanceEngine performanceEngine) {
        this.performanceEngine = performanceEngine;
    }

    @GetMapping("/portfolio")
    public PortfolioPerformanceResponse portfolio(@RequestParam(defaultValue = "1Y") String range) {
        return performanceEngine.performance(range);
    }
}
