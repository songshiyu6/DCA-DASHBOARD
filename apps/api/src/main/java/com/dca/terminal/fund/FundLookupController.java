package com.dca.terminal.fund;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/funds")
public class FundLookupController {
    private final EastMoneyFundLookupService lookupService;

    public FundLookupController(EastMoneyFundLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @GetMapping("/lookup")
    public EastMoneyFundLookupService.FundLookupResponse lookup(@RequestParam String code) {
        return lookupService.lookup(code);
    }
}
