package com.dca.terminal.fund;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.dca.terminal.fund.AutoDcaDtos.ProjectionResponse;
import static com.dca.terminal.fund.AutoDcaDtos.RuleRequest;
import static com.dca.terminal.fund.AutoDcaDtos.RuleResponse;

@RestController
@RequestMapping("/api/v1/auto-dca")
public class AutoDcaController {
    private final AutoDcaService service;

    public AutoDcaController(AutoDcaService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    public List<RuleResponse> list() { return service.list(); }

    @GetMapping("/rules/{id}")
    public RuleResponse get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@Valid @RequestBody RuleRequest request) { return service.create(request); }

    @PutMapping("/rules/{id}")
    public RuleResponse update(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/rules/{id}/projection")
    public ProjectionResponse projection(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "MONTH") AutoDcaProjectionEngine.GroupBy groupBy,
            @RequestParam(defaultValue = "false") boolean includeDaily) {
        return service.projection(id, groupBy, includeDaily);
    }
}
