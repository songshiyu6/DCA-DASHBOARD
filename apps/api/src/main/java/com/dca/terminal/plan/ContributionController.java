package com.dca.terminal.plan;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.plan.ContributionDtos.ContributionAnalysisResponse;
import com.dca.terminal.plan.ContributionDtos.InitialCapitalRequest;
import com.dca.terminal.transaction.TransactionEntity;
import com.dca.terminal.transaction.TransactionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
    private final PlanService planService;
    private final TransactionService transactionService;

    public ContributionController(ContributionAnalysisService service, PlanService planService,
                                  TransactionService transactionService) {
        this.service = service;
        this.planService = planService;
        this.transactionService = transactionService;
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
        InvestmentPlanEntity plan = planService.getEntity(planId);
        TransactionEntity transaction = transactionService.get(transactionId);
        if (!plan.getStartDate().equals(transaction.getTradeDate())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INITIAL_CONTRIBUTION_START_DATE_ONLY",
                    "Initial capital can only be recorded on the investment plan start date");
        }
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
