package com.dca.terminal.plan;

import com.dca.terminal.plan.ContributionDtos.ContributionAnalysisResponse;
import com.dca.terminal.plan.ContributionDtos.ClassificationAuditResponse;
import com.dca.terminal.plan.ContributionDtos.ClassificationCommitRequest;
import com.dca.terminal.plan.ContributionDtos.ClassificationCommitResponse;
import com.dca.terminal.plan.ContributionDtos.ClassificationPreviewRequest;
import com.dca.terminal.plan.ContributionDtos.ClassificationPreviewResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
public class ContributionController {
    private final ContributionAnalysisService service;
    private final ContributionClassificationService classificationService;

    public ContributionController(ContributionAnalysisService service,
                                  ContributionClassificationService classificationService) {
        this.service = service;
        this.classificationService = classificationService;
    }

    @GetMapping("/{planId}/contribution-analysis")
    public ContributionAnalysisResponse analysis(@PathVariable UUID planId) {
        return service.analyze(planId);
    }

    @PostMapping("/{planId}/contribution-classifications/preview")
    public ClassificationPreviewResponse previewClassifications(
            @PathVariable UUID planId,
            @Valid @RequestBody ClassificationPreviewRequest request) {
        return classificationService.preview(planId, request);
    }

    @PostMapping("/{planId}/contribution-classifications/commit")
    public ClassificationCommitResponse commitClassifications(
            @PathVariable UUID planId,
            @Valid @RequestBody ClassificationCommitRequest request) {
        ContributionClassificationService.CommitResult result = classificationService.commit(planId, request);
        return new ClassificationCommitResponse(result.batchId(), result.transactionIds(), service.analyze(planId));
    }

    @GetMapping("/{planId}/contribution-classifications/audit")
    public List<ClassificationAuditResponse> classificationAudit(@PathVariable UUID planId) {
        return classificationService.audit(planId);
    }
}
