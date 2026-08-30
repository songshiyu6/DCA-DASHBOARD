package com.dca.terminal.plan;

import com.dca.terminal.plan.ContributionDtos.ContributionAnalysisResponse;
import com.dca.terminal.plan.ContributionDtos.InitialCapitalRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
public class ContributionController {
    private final ContributionAnalysisService service;

    public ContributionController(ContributionAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/{planId}/contribution-analysis")
    public ContributionAnalysisResponse analysis(@PathVariable UUID planId) {
        return service.analyze(planId);
    }

    @PutMapping("/{planId}/initial-capital")
    public ContributionAnalysisResponse updateInitialCapital(@PathVariable UUID planId,
                                                              @Valid @RequestBody InitialCapitalRequest request) {
        service.setInitialCapital(planId, request.amount());
        return service.analyze(planId);
    }

    @PutMapping("/{planId}/contributions/{transactionId}/initial")
    public ContributionAnalysisResponse classifyInitial(@PathVariable UUID planId,
                                                         @PathVariable UUID transactionId) {
        service.classifyInitial(planId, transactionId);
        return service.analyze(planId);
    }

    @DeleteMapping("/{planId}/contributions/{transactionId}/initial")
    public ContributionAnalysisResponse unclassifyInitial(@PathVariable UUID planId,
                                                           @PathVariable UUID transactionId) {
        service.unclassifyInitial(planId, transactionId);
        return service.analyze(planId);
    }
}
