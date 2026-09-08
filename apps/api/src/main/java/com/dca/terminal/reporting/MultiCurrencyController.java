package com.dca.terminal.reporting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reporting")
public class MultiCurrencyController {
    private final MultiCurrencyReportingService service;

    public MultiCurrencyController(MultiCurrencyReportingService service) {
        this.service = service;
    }

    @GetMapping("/multicurrency")
    public MultiCurrencyDtos.Response report(@RequestParam(defaultValue = "ALL") String range) {
        return service.report(range);
    }
}
