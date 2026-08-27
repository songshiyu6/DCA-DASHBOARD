package com.dca.terminal.plan;

import com.dca.terminal.plan.PlanDtos.ContributionProgress;
import com.dca.terminal.plan.PlanDtos.CycleResponse;
import com.dca.terminal.plan.PlanDtos.PlanRequest;
import com.dca.terminal.plan.PlanDtos.PlanResponse;
import com.dca.terminal.plan.PlanDtos.RecommendationResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {
    private final PlanService service;

    public PlanController(PlanService service) { this.service = service; }

    @GetMapping
    public List<PlanResponse> list() { return service.list(); }

    @GetMapping("/{id}")
    public PlanResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse create(@Valid @RequestBody PlanRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    public PlanResponse update(@PathVariable UUID id, @Valid @RequestBody PlanRequest request) { return service.update(id, request); }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) { service.archive(id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAsArchive(@PathVariable UUID id) { service.archive(id); }

    @GetMapping("/{id}/cycles")
    public List<CycleResponse> cycles(@PathVariable UUID id) { return service.cycles(id); }

    @GetMapping("/{id}/cycles/{period}")
    public CycleResponse cycle(@PathVariable UUID id, @PathVariable String period) { return service.cycle(id, period); }

    @GetMapping("/{id}/recommendation")
    public RecommendationResponse recommendation(@PathVariable UUID id,
                                                 @RequestParam(required = false) BigDecimal amount) {
        return service.recommendation(id, amount);
    }

    @GetMapping("/{id}/progress")
    public ContributionProgress progress(@PathVariable UUID id) { return service.contributionProgress(id); }
}
