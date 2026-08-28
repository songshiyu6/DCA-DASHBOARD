package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.plan.PlanService;
import jakarta.validation.Valid;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import static com.dca.terminal.transaction.TransactionDtos.CsvCommitRequest;
import static com.dca.terminal.transaction.TransactionDtos.CsvCommitResponse;
import static com.dca.terminal.transaction.TransactionDtos.CsvPreviewResponse;
import static com.dca.terminal.transaction.TransactionDtos.CsvRowPreview;
import static com.dca.terminal.transaction.TransactionDtos.CsvRowRequest;
import static com.dca.terminal.transaction.TransactionDtos.TransactionRequest;
import static com.dca.terminal.transaction.TransactionDtos.TransactionResponse;

@Service
public class TransactionService {
    private static final Object LEDGER_ORDER_LOCK = new Object();
    private final TransactionRepository transactionRepository;
    private final InstrumentRepository instrumentRepository;
    private final SplitEventRepository splitEventRepository;
    private final MarketDataService marketDataService;
    private final PlanService planService;
    private final PortfolioService portfolioService;
    private final PortfolioSnapshotInvalidator snapshotInvalidator;
    private final Clock clock;
    private final ZoneId zone;

    public TransactionService(TransactionRepository transactionRepository, InstrumentRepository instrumentRepository,
                              SplitEventRepository splitEventRepository, MarketDataService marketDataService,
                              PlanService planService, PortfolioService portfolioService,
                              PortfolioSnapshotInvalidator snapshotInvalidator, Clock clock, ZoneId zone) {
        this.transactionRepository = transactionRepository;
        this.instrumentRepository = instrumentRepository;
        this.splitEventRepository = splitEventRepository;
        this.marketDataService = marketDataService;
        this.planService = planService;
        this.portfolioService = portfolioService;
        this.snapshotInvalidator = snapshotInvalidator;
        this.clock = clock;
        this.zone = zone;
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(String symbol, LocalDate from, LocalDate to) {
        return transactionRepository.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc().stream()
                .filter(transaction -> symbol == null || transaction.getInstrument().getSymbol().equalsIgnoreCase(symbol))
                .filter(transaction -> from == null || !transaction.getTradeDate().isBefore(from))
                .filter(transaction -> to == null || !transaction.getTradeDate().isAfter(to))
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TransactionEntity get(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found"));
    }

    @Transactional
    public TransactionEntity create(@Valid TransactionRequest request) {
        TransactionEntity entity = transactionRepository.saveAndFlush(toEntity(request, null));
        validateLedger();
        snapshotInvalidator.invalidateFrom(entity.getTradeDate());
        portfolioService.rebuildTodaySnapshot();
        return entity;
    }

    @Transactional
    public TransactionEntity update(UUID id, @Valid TransactionRequest request) {
        TransactionEntity entity = get(id);
        LocalDate oldTradeDate = entity.getTradeDate();
        apply(entity, request);
        TransactionEntity saved = transactionRepository.saveAndFlush(entity);
        validateLedger();
        snapshotInvalidator.invalidateFrom(oldTradeDate.isBefore(saved.getTradeDate()) ? oldTradeDate : saved.getTradeDate());
        portfolioService.rebuildTodaySnapshot();
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        TransactionEntity entity = get(id);
        LocalDate oldTradeDate = entity.getTradeDate();
        transactionRepository.delete(entity);
        transactionRepository.flush();
        validateLedger();
        snapshotInvalidator.invalidateFrom(oldTradeDate);
        portfolioService.rebuildTodaySnapshot();
    }

    @Transactional(readOnly = true)
    public CsvPreviewResponse preview(MultipartFile file) {
        UUID batchId = UUID.randomUUID();
        List<CsvRowPreview> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) throw invalidCsv("CSV is empty");
            List<String> columns = parseLine(header);
            java.util.Set<String> normalizedColumns = columns.stream()
                    .map(value -> value.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            if (!normalizedColumns.containsAll(java.util.Set.of("date", "type", "symbol", "quantity", "price", "fee"))) {
                throw invalidCsv("CSV must contain date,type,symbol,quantity,price,fee columns");
            }
            int rowNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;
                List<String> values = parseLine(line);
                CsvRowRequest row = rowFromColumns(columns, values);
                List<String> errors = validateRow(row);
                String fingerprint = fingerprint(row);
                if (rows.stream().anyMatch(previous -> previous.fingerprint().equals(fingerprint))
                        || transactionRepository.existsByImportFingerprint(fingerprint)) {
                    errors = new ArrayList<>(errors);
                    errors.add("Duplicate row");
                }
                rows.add(new CsvRowPreview(rowNumber, row, errors.isEmpty(), errors, fingerprint));
            }
        } catch (IOException exception) {
            throw invalidCsv("CSV could not be read");
        }
        int valid = (int) rows.stream().filter(CsvRowPreview::valid).count();
        return new CsvPreviewResponse(batchId, rows.size(), valid, rows.size() - valid, rows);
    }

    @Transactional
    public CsvCommitResponse commit(@Valid CsvCommitRequest request) {
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw invalidCsv("CSV contains no rows");
        }
        Set<String> fingerprints = new HashSet<>();
        List<TransactionRequest> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (CsvRowRequest row : request.rows()) {
            List<String> rowErrors = validateRow(row);
            String fingerprint = fingerprint(row);
            if (!fingerprints.add(fingerprint)) rowErrors = append(rowErrors, "Duplicate row");
            if (transactionRepository.existsByImportFingerprint(fingerprint)) {
                rowErrors = append(rowErrors, "Row was already imported");
            }
            if (!rowErrors.isEmpty()) errors.add(String.join(", ", rowErrors));
            else parsed.add(requestToTransaction(row));
        }
        if (!errors.isEmpty()) throw invalidCsv("CSV row invalid: " + String.join("; ", errors));

        LocalDate batchStart = parsed.stream().map(TransactionRequest::tradeDate).min(LocalDate::compareTo).orElseThrow();
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < request.rows().size(); index++) {
            CsvRowRequest row = request.rows().get(index);
            String fingerprint = fingerprint(row);
            TransactionEntity entity = toEntity(parsed.get(index), request.batchId());
            entity.setImportFingerprint(fingerprint);
            ids.add(transactionRepository.save(entity).getId());
        }
        transactionRepository.flush();
        validateLedger();
        snapshotInvalidator.invalidateFrom(batchStart);
        portfolioService.rebuildTodaySnapshot();
        return new CsvCommitResponse(request.batchId(), ids.size(), ids);
    }

    private TransactionEntity toEntity(TransactionRequest request, UUID batchId) {
        validateRequest(request);
        TransactionEntity entity = new TransactionEntity();
        entity.setLedgerOrder(nextLedgerOrder());
        apply(entity, request);
        entity.setImportBatchId(batchId);
        return entity;
    }

    private void apply(TransactionEntity entity, TransactionRequest request) {
        validateRequest(request);
        InstrumentEntity instrument = instrumentRepository.findBySymbolIgnoreCase(request.symbol().trim())
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND", "ETF not found: " + request.symbol()));
        UUID cycleId = request.planCycleId();
        if (cycleId != null) planService.validateCycleForTransaction(cycleId, instrument.getId(),
                request.type(), request.tradeDate());
        entity.setInstrument(instrument);
        entity.setTransactionType(request.type());
        entity.setTradeDate(request.tradeDate());
        entity.setQuantity(request.quantity());
        entity.setUnitPrice(request.unitPrice());
        entity.setAmount(request.amount());
        entity.setFee(request.fee() == null ? BigDecimal.ZERO : request.fee());
        entity.setPlanCycleId(cycleId);
        entity.setNotes(request.notes());
        entity.setCurrency("USD");
    }

    private void validateLedger() {
        List<TransactionEntity> transactions = transactionRepository.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
        if (transactions.isEmpty()) return;
        LocalDate asOf = transactions.stream().map(TransactionEntity::getTradeDate).max(LocalDate::compareTo).orElse(LocalDate.now());
        Map<UUID, List<SplitEventEntity>> splits = new java.util.HashMap<>();
        transactions.stream().map(transaction -> transaction.getInstrument().getId()).distinct()
                .forEach(id -> splits.put(id,
                        splitEventRepository.findAllByInstrumentIdAndEffectiveDateLessThanEqualOrderByEffectiveDateAsc(id, asOf)));
        FifoCalculator.calculate(transactions, splits, asOf, marketDataService.providerPriority());
    }

    private void validateRequest(TransactionRequest request) {
        if (request == null || request.type() == null || request.tradeDate() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", "Type and trade date are required");
        }
        if (request.tradeDate().isAfter(LocalDate.now(clock.withZone(zone)))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "FUTURE_TRADE_DATE_NOT_ALLOWED",
                    "Trade date cannot be in the future");
        }
        if (request.type() == TransactionType.BUY || request.type() == TransactionType.SELL) {
            if (request.quantity() == null || request.quantity().signum() <= 0 || request.unitPrice() == null
                    || request.unitPrice().signum() < 0) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                        "BUY and SELL require a positive quantity and non-negative unit price");
            }
            if (request.amount() != null) throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                    "BUY and SELL do not accept amount");
        } else if (request.amount() == null || request.amount().signum() < 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                    "DIVIDEND and FEE require a non-negative amount");
        } else if (request.fee() != null && request.fee().signum() > 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                    "DIVIDEND and FEE transactions must use amount for fees");
        }
        if (request.fee() != null && request.fee().signum() < 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", "Fee cannot be negative");
        }
    }

    private TransactionResponse toResponse(TransactionEntity entity) {
        return new TransactionResponse(entity.getId(), entity.getInstrument().getSymbol(), entity.getInstrument().getName(),
                entity.getTransactionType(), entity.getTradeDate(), entity.getQuantity(), entity.getUnitPrice(),
                entity.getAmount(), entity.getFee(), entity.getCurrency(), entity.getPlanCycleId(), entity.getNotes(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getLedgerOrder());
    }

    private CsvRowRequest rowFromColumns(List<String> headers, List<String> values) {
        java.util.Map<String, String> data = new java.util.HashMap<>();
        for (int i = 0; i < headers.size() && i < values.size(); i++) {
            String header = headers.get(i).replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
            data.put(header, values.get(i).trim());
        }
        String planCycleId = data.get("plancycleid");
        if (planCycleId == null) planCycleId = data.get("plan_cycle_id");
        return new CsvRowRequest(data.getOrDefault("date", ""), data.getOrDefault("type", ""),
                data.getOrDefault("symbol", ""), data.get("quantity"), data.get("price"), data.get("fee"),
                data.get("amount"), planCycleId, data.get("notes"));
    }

    private List<String> validateRow(CsvRowRequest row) {
        List<String> errors = new ArrayList<>();
        if (row == null) return List.of("row is required");
        LocalDate tradeDate = parseDate(row.date());
        if (tradeDate == null) errors.add("date must be YYYY-MM-DD");
        else if (tradeDate.isAfter(LocalDate.now(clock.withZone(zone)))) errors.add("trade date cannot be in the future");
        try { TransactionType.valueOf(row.type().trim().toUpperCase(Locale.ROOT)); } catch (Exception ignored) { errors.add("type must be BUY, SELL, DIVIDEND or FEE"); }
        if (row.symbol() == null || row.symbol().isBlank()) errors.add("symbol is required");
        else if (instrumentRepository.findBySymbolIgnoreCase(row.symbol().trim()).isEmpty()) errors.add("symbol is not tracked");
        TransactionType type = parseType(row.type());
        if (type == TransactionType.BUY || type == TransactionType.SELL) {
            if (decimal(row.quantity()) == null || decimal(row.quantity()).signum() <= 0) errors.add("quantity is required");
            if (decimal(row.price()) == null || decimal(row.price()).signum() < 0) errors.add("price is required");
            if (row.amount() != null && !row.amount().isBlank()) errors.add("amount is not valid for BUY/SELL");
        } else if (type == TransactionType.DIVIDEND || type == TransactionType.FEE) {
            if (decimal(row.amount()) == null || decimal(row.amount()).signum() < 0) errors.add("amount is required");
        }
        if (row.fee() != null && !row.fee().isBlank() && (decimal(row.fee()) == null || decimal(row.fee()).signum() < 0)) {
            errors.add("fee is invalid");
        } else if ((type == TransactionType.DIVIDEND || type == TransactionType.FEE)
                && decimal(row.fee()) != null && decimal(row.fee()).signum() > 0) {
            errors.add("DIVIDEND and FEE transactions must use amount for fees");
        }
        if (row.planCycleId() != null && !row.planCycleId().isBlank()) {
            try {
                UUID cycleId = UUID.fromString(row.planCycleId().trim());
                InstrumentEntity instrument = row.symbol() == null || row.symbol().isBlank() ? null
                        : instrumentRepository.findBySymbolIgnoreCase(row.symbol().trim()).orElse(null);
                TransactionType cycleType = parseType(row.type());
                LocalDate cycleDate = parseDate(row.date());
                if (instrument != null && cycleType != null && cycleDate != null) {
                    planService.validateCycleForTransaction(cycleId, instrument.getId(), cycleType, cycleDate);
                }
            } catch (IllegalArgumentException exception) {
                errors.add("planCycleId must be a UUID");
            } catch (DomainException exception) {
                errors.add(exception.getMessage());
            }
        }
        return errors;
    }

    private TransactionRequest requestToTransaction(CsvRowRequest row) {
        TransactionType type = TransactionType.valueOf(row.type().trim().toUpperCase(Locale.ROOT));
        UUID cycleId = row.planCycleId() == null || row.planCycleId().isBlank()
                ? null : UUID.fromString(row.planCycleId().trim());
        return new TransactionRequest(row.symbol(), type, LocalDate.parse(row.date()), decimal(row.quantity()),
                decimal(row.price()), decimal(row.amount()), decimal(row.fee()), cycleId, row.notes());
    }

    private List<String> append(List<String> errors, String error) {
        List<String> result = new ArrayList<>(errors);
        result.add(error);
        return result;
    }

    private TransactionType parseType(String value) {
        try { return value == null ? null : TransactionType.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return null; }
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new BigDecimal(value.trim()); } catch (NumberFormatException ignored) { return null; }
    }

    private LocalDate parseDate(String value) {
        try { return value == null ? null : LocalDate.parse(value.trim()); }
        catch (Exception ignored) { return null; }
    }

    private String fingerprint(CsvRowRequest row) {
        if (row == null) return "null-row";
        String source = String.join("|", row.date() == null ? "" : row.date().trim(),
                row.type() == null ? "" : row.type().trim().toUpperCase(Locale.ROOT),
                row.symbol() == null ? "" : row.symbol().trim().toUpperCase(Locale.ROOT), canonicalDecimal(row.quantity()),
                canonicalDecimal(row.price()), canonicalDecimal(row.fee()), canonicalDecimal(row.amount()),
                row.planCycleId() == null ? "" : row.planCycleId().trim().toLowerCase(Locale.ROOT),
                row.notes() == null ? "" : row.notes().trim());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String canonicalDecimal(String value) {
        if (value == null || value.isBlank()) return "";
        BigDecimal parsed = decimal(value);
        return parsed == null ? value.trim() : parsed.stripTrailingZeros().toPlainString();
    }

    private long nextLedgerOrder() {
        synchronized (LEDGER_ORDER_LOCK) {
            return transactionRepository.findTopByOrderByLedgerOrderDesc()
                    .map(TransactionEntity::getLedgerOrder).orElse(0L) + 1L;
        }
    }

    private List<String> parseLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else quoted = !quoted;
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else current.append(character);
        }
        if (quoted) throw invalidCsv("Unclosed CSV quote");
        values.add(current.toString());
        return values;
    }

    private DomainException invalidCsv(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_CSV", message);
    }
}
