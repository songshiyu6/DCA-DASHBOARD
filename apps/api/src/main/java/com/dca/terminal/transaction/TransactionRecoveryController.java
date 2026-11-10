package com.dca.terminal.transaction;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transactions/trash")
public class TransactionRecoveryController {
    private final TransactionRecoveryService service;

    public TransactionRecoveryController(TransactionRecoveryService service) { this.service = service; }

    @GetMapping
    public List<TransactionRecoveryService.DeletedTransaction> list() { return service.list(); }

    @PostMapping("/{id}/restore")
    public Map<String, UUID> restore(@PathVariable UUID id) {
        return Map.of("id", service.restore(id).getId());
    }
}
