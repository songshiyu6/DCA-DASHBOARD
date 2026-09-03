package com.dca.terminal.benchmark;

import com.dca.terminal.benchmark.BenchmarkDtos.BenchmarkType;
import com.dca.terminal.benchmark.BenchmarkDtos.HistoryResponse;
import com.dca.terminal.benchmark.BenchmarkDtos.SearchResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/benchmarks")
public class BenchmarkController {
    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam String q) {
        return benchmarkService.search(q);
    }

    @GetMapping("/history")
    public HistoryResponse history(@RequestParam String symbol,
                                   @RequestParam(required = false) String name,
                                   @RequestParam BenchmarkType type,
                                   @RequestParam(defaultValue = "5Y") String range) {
        return benchmarkService.history(symbol, name, type, range);
    }
}
