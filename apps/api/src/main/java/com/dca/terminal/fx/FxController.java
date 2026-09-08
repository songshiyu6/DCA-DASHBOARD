package com.dca.terminal.fx;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fx/usd-cny")
public class FxController {
    private final FxService service;
    private final Clock clock;

    public FxController(FxService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public FxDtos.FxSeriesResponse series(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate end = endDate == null ? LocalDate.now(clock) : endDate;
        LocalDate start = startDate == null ? end.minusYears(1) : startDate;
        return service.usdCny(start, end);
    }

    @PostMapping("/sync")
    public FxDtos.FxSyncResponse sync(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate end = endDate == null ? LocalDate.now(clock) : endDate;
        LocalDate start = startDate == null ? end.minusYears(5) : startDate;
        return service.syncUsdCny(start, end);
    }
}
