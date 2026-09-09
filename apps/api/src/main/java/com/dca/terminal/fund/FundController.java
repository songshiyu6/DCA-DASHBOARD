package com.dca.terminal.fund;

import jakarta.validation.Valid;
import java.time.LocalDate;
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

import static com.dca.terminal.fund.FundDtos.FundCalendarResponse;
import static com.dca.terminal.fund.FundDtos.FundDeleteResponse;
import static com.dca.terminal.fund.FundDtos.FundRequest;
import static com.dca.terminal.fund.FundDtos.FundResponse;
import static com.dca.terminal.fund.FundDtos.FundSyncResponse;
import static com.dca.terminal.fund.FundDtos.NavRequest;
import static com.dca.terminal.fund.FundDtos.NavResponse;

@RestController
@RequestMapping("/api/v1/funds")
public class FundController {
    private final FundService service;
    private final FundDataSyncService syncService;

    public FundController(FundService service, FundDataSyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    @GetMapping
    public List<FundResponse> list() { return service.list(); }

    @GetMapping("/{id}")
    public FundResponse get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FundResponse create(@Valid @RequestBody FundRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    public FundResponse update(@PathVariable UUID id, @Valid @RequestBody FundRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public FundDeleteResponse delete(@PathVariable UUID id) {
        return new FundDeleteResponse(service.delete(id));
    }

    @PutMapping("/{id}/nav")
    public NavResponse putNav(@PathVariable UUID id, @Valid @RequestBody NavRequest request) {
        return service.putNav(id, request);
    }

    @GetMapping("/{id}/nav")
    public List<NavResponse> nav(@PathVariable UUID id) { return service.navHistory(id); }

    @PostMapping("/{id}/sync")
    public FundSyncResponse sync(@PathVariable UUID id,
                                 @RequestParam(required = false) LocalDate startDate,
                                 @RequestParam(required = false) LocalDate endDate) {
        return syncService.sync(id, startDate, endDate);
    }

    @GetMapping("/{id}/calendar")
    public FundCalendarResponse calendar(@PathVariable UUID id,
                                         @RequestParam(required = false) LocalDate startDate,
                                         @RequestParam(required = false) LocalDate endDate) {
        return syncService.calendar(id, startDate, endDate);
    }
}
