package com.dca.terminal.transaction;

import com.dca.terminal.transaction.TransactionDtos.CsvCommitRequest;
import com.dca.terminal.transaction.TransactionDtos.CsvCommitResponse;
import com.dca.terminal.transaction.TransactionDtos.CsvPreviewResponse;
import com.dca.terminal.transaction.TransactionDtos.TransactionRequest;
import com.dca.terminal.transaction.TransactionDtos.TransactionResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final TransactionService service;

    public TransactionController(TransactionService service) { this.service = service; }

    @GetMapping
    public List<TransactionResponse> list(@RequestParam(required = false) String symbol,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        return service.list(symbol, from, to);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) { return response(service.get(id)); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@Valid @RequestBody TransactionRequest request) {
        return response(service.create(request));
    }

    @PutMapping("/{id}")
    public TransactionResponse update(@PathVariable UUID id, @Valid @RequestBody TransactionRequest request) {
        return response(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { service.delete(id); }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvPreviewResponse preview(@RequestPart("file") MultipartFile file) { return service.preview(file); }

    @PostMapping("/import/commit")
    public CsvCommitResponse commit(@Valid @RequestBody CsvCommitRequest request) { return service.commit(request); }

    private TransactionResponse response(TransactionEntity entity) {
        return new TransactionResponse(entity.getId(), entity.getInstrument().getSymbol(), entity.getInstrument().getName(),
                entity.getTransactionType(), entity.getTradeDate(), entity.getQuantity(), entity.getUnitPrice(),
                entity.getAmount(), entity.getFee(), entity.getCurrency(), entity.getPlanCycleId(), entity.getNotes(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getLedgerOrder());
    }
}
