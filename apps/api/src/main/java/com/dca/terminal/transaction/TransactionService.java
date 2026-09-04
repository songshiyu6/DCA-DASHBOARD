package com.dca.terminal.transaction;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.marketdata.MarketDataEntities.SplitEventEntity;
import com.dca.terminal.marketdata.MarketDataService;
import com.dca.terminal.marketdata.SplitEventRepository;
import com.dca.terminal.observability.ObservabilityMetrics;
import com.dca.terminal.plan.PlanService;
import com.dca.terminal.portfolio.PortfolioService;
import com.dca.terminal.portfolio.PortfolioSnapshotInvalidator;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
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
    private static final long DEFAULT_MAX_CSV_BYTES = 1_048_576L;
    private static final int DEFAULT_MAX_CSV_ROWS = 10_000;
    private static final int DEFAULT_MAX_CSV_FIELD_LENGTH = 1_000;
    private static final Set<String> KNOWN_CSV_FIELDS = Set.of(
            "date", "type", "symbol", "quantity", "price", "fee", "amount",
            "plancycleid", "plan_cycle_id", "contributiontype", "contribution_type",
            "contributionplanid", "contribution_plan_id", "notes");
    private final TransactionRepository transactionRepository;
    private final InstrumentRepository instrumentRepository;
    private final SplitEventRepository splitEventRepository;
    private final MarketDataService marketDataService;
    private final PlanService planService;
    private final PortfolioService portfolioService;
    private final PortfolioSnapshotInvalidator snapshotInvalidator;
    private final Clock clock;
    private final ZoneId zone;
    private final long maxCsvBytes;
    private final int maxCsvRows;
    private final int maxCsvFieldLength;
    private final MeterRegistry meterRegistry;

    public TransactionService(TransactionRepository transactionRepository, InstrumentRepository instrumentRepository,
                              SplitEventRepository splitEventRepository, MarketDataService marketDataService,
                              PlanService planService, PortfolioService portfolioService,
                              PortfolioSnapshotInvalidator snapshotInvalidator, Clock clock, ZoneId zone) {
        this(transactionRepository, instrumentRepository, splitEventRepository, marketDataService, planService,
                portfolioService, snapshotInvalidator, clock, zone, ObservabilityMetrics.noop());
    }

    public TransactionService(TransactionRepository transactionRepository, InstrumentRepository instrumentRepository,
                              SplitEventRepository splitEventRepository, MarketDataService marketDataService,
                              PlanService planService, PortfolioService portfolioService,
                              PortfolioSnapshotInvalidator snapshotInvalidator, Clock clock, ZoneId zone,
                              MeterRegistry meterRegistry) {
        this(transactionRepository, instrumentRepository, splitEventRepository, marketDataService, planService,
                portfolioService, snapshotInvalidator, clock, zone, DEFAULT_MAX_CSV_BYTES, DEFAULT_MAX_CSV_ROWS,
                DEFAULT_MAX_CSV_FIELD_LENGTH, meterRegistry);
    }

    @Autowired
    public TransactionService(TransactionRepository transactionRepository, InstrumentRepository instrumentRepository,
                              SplitEventRepository splitEventRepository, MarketDataService marketDataService,
                              PlanService planService, PortfolioService portfolioService,
                              PortfolioSnapshotInvalidator snapshotInvalidator, Clock clock, ZoneId zone,
                              @Value("${spring.servlet.multipart.max-file-size:1MB}") String maxCsvSize,
                              @Value("${dca.transaction.max-csv-rows:10000}") int maxCsvRows,
                              @Value("${dca.transaction.max-csv-field-length:1000}") int maxCsvFieldLength,
                              MeterRegistry meterRegistry) {
        this(transactionRepository, instrumentRepository, splitEventRepository, marketDataService, planService,
                portfolioService, snapshotInvalidator, clock, zone, DataSize.parse(maxCsvSize).toBytes(), maxCsvRows,
                maxCsvFieldLength, meterRegistry);
    }

    TransactionService(TransactionRepository transactionRepository, InstrumentRepository instrumentRepository,
                       SplitEventRepository splitEventRepository, MarketDataService marketDataService,
                       PlanService planService, PortfolioService portfolioService,
                       PortfolioSnapshotInvalidator snapshotInvalidator, Clock clock, ZoneId zone,
                       long maxCsvBytes, int maxCsvRows, int maxCsvFieldLength) {
        this(transactionRepository, instrumentRepository, splitEventRepository, marketDataService, planService,
                portfolioService, snapshotInvalidator, clock, zone, maxCsvBytes, maxCsvRows, maxCsvFieldLength,
                ObservabilityMetrics.noop());
    }

    TransactionService(TransactionRepository transactionRepository, InstrumentRepository instrumentRepository,
                       SplitEventRepository splitEventRepository, MarketDataService marketDataService,
                       PlanService planService, PortfolioService portfolioService,
                       PortfolioSnapshotInvalidator snapshotInvalidator, Clock clock, ZoneId zone,
                       long maxCsvBytes, int maxCsvRows, int maxCsvFieldLength, MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.instrumentRepository = instrumentRepository;
        this.splitEventRepository = splitEventRepository;
        this.marketDataService = marketDataService;
        this.planService = planService;
        this.portfolioService = portfolioService;
        this.snapshotInvalidator = snapshotInvalidator;
        this.clock = clock;
        this.zone = zone;
        if (maxCsvBytes <= 0 || maxCsvRows <= 0 || maxCsvFieldLength <= 0) {
            throw new IllegalArgumentException("CSV limits must be positive");
        }
        this.maxCsvBytes = maxCsvBytes;
        this.maxCsvRows = maxCsvRows;
        this.maxCsvFieldLength = maxCsvFieldLength;
        this.meterRegistry = meterRegistry == null ? ObservabilityMetrics.noop() : meterRegistry;
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(String symbol, LocalDate from, LocalDate to) {
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim();
        return transactionRepository.findForList(normalizedSymbol, from, to).stream()
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
        ensureCsvFileSize(file);
        UUID batchId = UUID.randomUUID();
        List<CsvRowPreview> rows = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) throw invalidCsv("CSV is empty");
            List<String> columns = parseLine(header);
            List<String> headerErrors = csvFieldLengthErrors(columns, columns);
            if (!headerErrors.isEmpty()) throw csvFieldTooLong(1, headerErrors);
            Set<String> normalizedColumns = columns.stream()
                    .map(value -> value.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            if (!normalizedColumns.containsAll(Set.of("date", "type"))) {
                throw invalidCsv("CSV must contain date,type columns");
            }
            int rowNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;
                if (rows.size() >= maxCsvRows) throw csvTooManyRows();
                List<String> values = parseLine(line);
                CsvRowRequest row = rowFromColumns(columns, values);
                List<String> errors = mergeErrors(csvFieldLengthErrors(columns, values), validateRow(row));
                String fingerprint = fingerprint(row);
                if (!fingerprints.add(fingerprint) || transactionRepository.existsByImportFingerprint(fingerprint)) {
                    errors = append(errors, "Duplicate row");
                }
                rows.add(new CsvRowPreview(rowNumber, row, errors.isEmpty(), errors, fingerprint));
            }
        } catch (IOException exception) {
            throw invalidCsv("CSV could not be read");
        }
        int valid = (int) rows.stream().filter(CsvRowPreview::valid).count();
        int invalid = rows.size() - valid;
        int duplicates = (int) rows.stream().filter(row -> row.errors().stream()
                .anyMatch(error -> error.contains("Duplicate") || error.contains("already imported"))).count();
        recordCsv(rows.size(), invalid, duplicates);
        return new CsvPreviewResponse(batchId, rows.size(), valid, invalid, rows);
    }

    @Transactional
    public CsvCommitResponse commit(@Valid CsvCommitRequest request) {
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw invalidCsv("CSV contains no rows");
        }
        if (request.rows().size() > maxCsvRows) throw csvTooManyRows();
        Set<String> fingerprints = new HashSet<>();
        List<TransactionRequest> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < request.rows().size(); index++) {
            CsvRowRequest row = request.rows().get(index);
            List<String> rowErrors = csvFieldLengthErrors(row);
            rowErrors.addAll(validateRow(row));
            String fingerprint = fingerprint(row);
            if (!fingerprints.add(fingerprint)) rowErrors = append(rowErrors, "Duplicate row");
            if (transactionRepository.existsByImportFingerprint(fingerprint)) {
                rowErrors = append(rowErrors, "Row was already imported");
            }
            if (!rowErrors.isEmpty()) errors.add("row " + (index + 2) + ": " + String.join(", ", rowErrors));
            else parsed.add(requestToTransaction(row));
        }
        int duplicates = (int) errors.stream().filter(error -> error.contains("Duplicate")
                || error.contains("already imported")).count();
        if (!errors.isEmpty()) {
            recordCsv(request.rows().size(), errors.size(), duplicates);
            throw invalidCsv("CSV row invalid: " + String.join("; ", errors));
        }
        recordCsv(request.rows().size(), 0, 0);

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
        entity.setLedgerOrder(transactionRepository.nextLedgerOrder());
        apply(entity, request);
        entity.setImportBatchId(batchId);
        return entity;
    }

    private void apply(TransactionEntity entity, TransactionRequest request) {
        validateRequest(request);
        InstrumentEntity instrument = resolveInstrument(request);
        UUID cycleId = request.planCycleId();
        if (cycleId != null) {
            if (request.type() != TransactionType.BUY || instrument == null) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "PLAN_CYCLE_REQUIRES_BUY",
                        "Only BUY transactions can be linked to a plan cycle");
            }
            planService.validateCycleForTransaction(cycleId, instrument.getId(), request.type(), request.tradeDate());
        }

        ContributionSource contribution = contributionSource(entity, request, cycleId);
        entity.setInstrument(instrument);
        entity.setTransactionType(request.type());
        entity.setTradeDate(request.tradeDate());
        entity.setQuantity(request.quantity());
        entity.setUnitPrice(request.unitPrice());
        entity.setAmount(request.amount());
        entity.setFee(request.fee() == null ? BigDecimal.ZERO : request.fee());
        entity.setPlanCycleId(cycleId);
        entity.setContributionType(contribution.type());
        entity.setContributionPlanId(contribution.planId());
        entity.setNotes(request.notes());
        entity.setCurrency("USD");
    }

    private InstrumentEntity resolveInstrument(TransactionRequest request) {
        String symbol = request.symbol() == null ? null : request.symbol().trim();
        if (request.type().requiresInstrument()) {
            if (symbol == null || symbol.isBlank()) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "INSTRUMENT_REQUIRED",
                        request.type() + " requires an instrument");
            }
            return trackedInstrument(symbol);
        }
        if (request.type().allowsOptionalInstrument()) {
            return symbol == null || symbol.isBlank() ? null : trackedInstrument(symbol);
        }
        if (symbol != null && !symbol.isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                    request.type() + " is an account cash event and does not accept an instrument");
        }
        return null;
    }

    private InstrumentEntity trackedInstrument(String symbol) {
        return instrumentRepository.findBySymbolIgnoreCase(symbol)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND",
                        "Instrument not found: " + symbol));
    }

    private ContributionSource contributionSource(TransactionEntity entity, TransactionRequest request, UUID cycleId) {
        ContributionType contributionType = request.contributionType();
        UUID contributionPlanId = request.contributionPlanId();

        if (request.type() == TransactionType.BUY) {
            if (cycleId != null) {
                if (contributionType != null && contributionType != ContributionType.DCA) {
                    throw new DomainException(HttpStatus.CONFLICT, "CONTRIBUTION_SOURCE_CONFLICT",
                            "A BUY linked to a plan cycle must be classified as DCA");
                }
                if (contributionPlanId != null) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_CONTRIBUTION_PLAN",
                            "DCA contribution plan is determined by the selected cycle");
                }
                return new ContributionSource(ContributionType.DCA, null);
            }
            if (contributionType == ContributionType.DCA) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "DCA_CONTRIBUTION_REQUIRES_CYCLE",
                        "A DCA BUY must be linked to a plan cycle");
            }
            if (contributionType == null && contributionPlanId == null
                    && entity.getTransactionType() == TransactionType.BUY) {
                contributionType = entity.getContributionType();
                contributionPlanId = entity.getContributionPlanId();
            }
            return validateContributionClassification(contributionType, contributionPlanId, request.tradeDate());
        }

        if (request.type() == TransactionType.DEPOSIT) {
            if (cycleId != null) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "PLAN_CYCLE_REQUIRES_BUY",
                        "DEPOSIT records funding; only the BUY execution can be linked to a plan cycle");
            }
            if (contributionType == null && contributionPlanId == null
                    && entity.getTransactionType() == TransactionType.DEPOSIT) {
                contributionType = entity.getContributionType();
                contributionPlanId = entity.getContributionPlanId();
            }
            if (contributionType == ContributionType.DCA) {
                if (contributionPlanId == null) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "DCA_CONTRIBUTION_REQUIRES_PLAN",
                            "A DCA deposit must identify its investment plan");
                }
                planService.getEntity(contributionPlanId);
                return new ContributionSource(contributionType, contributionPlanId);
            }
            return validateContributionClassification(contributionType, contributionPlanId, request.tradeDate());
        }

        if (contributionType != null || contributionPlanId != null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CONTRIBUTION_SOURCE_REQUIRES_FUNDING_OR_BUY",
                    "Contribution source is only valid for DEPOSIT or legacy BUY transactions");
        }
        return new ContributionSource(null, null);
    }

    private ContributionSource validateContributionClassification(ContributionType contributionType,
                                                                  UUID contributionPlanId,
                                                                  LocalDate tradeDate) {
        if (contributionType == ContributionType.INITIAL) {
            if (contributionPlanId == null) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "INITIAL_CONTRIBUTION_REQUIRES_PLAN",
                        "Initial capital must be linked to an investment plan");
            }
            LocalDate startDate = planService.getEntity(contributionPlanId).getStartDate();
            if (!tradeDate.equals(startDate)) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "INITIAL_CONTRIBUTION_START_DATE_ONLY",
                        "Initial capital can only be recorded on the investment plan start date");
            }
            return new ContributionSource(contributionType, contributionPlanId);
        }
        if (contributionType == ContributionType.UNPLANNED) {
            if (contributionPlanId != null) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_CONTRIBUTION_PLAN",
                        "Unplanned contributions cannot be linked to an investment plan");
            }
            return new ContributionSource(contributionType, null);
        }
        if (contributionPlanId != null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_CONTRIBUTION_PLAN",
                    "Contribution plan requires an explicit contribution type");
        }
        return new ContributionSource(null, null);
    }

    private void validateLedger() {
        List<TransactionEntity> transactions = transactionRepository.findAllByOrderByTradeDateAscLedgerOrderAscIdAsc();
        if (transactions.isEmpty()) return;
        LocalDate asOf = transactions.stream().map(TransactionEntity::getTradeDate)
                .max(LocalDate::compareTo).orElse(LocalDate.now(clock.withZone(zone)));
        Map<UUID, List<SplitEventEntity>> splits = new java.util.HashMap<>();
        transactions.stream().map(TransactionEntity::getInstrument).filter(java.util.Objects::nonNull)
                .map(InstrumentEntity::getId).distinct()
                .forEach(id -> splits.put(id,
                        splitEventRepository.findAllByInstrumentIdAndEffectiveDateLessThanEqualOrderByEffectiveDateAsc(id, asOf)));
        FifoCalculator.calculate(transactions, splits, asOf, marketDataService.providerPriority());
        CashLedgerCalculator.calculate(transactions, asOf);
    }

    private void validateRequest(TransactionRequest request) {
        if (request == null || request.type() == null || request.tradeDate() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", "Type and trade date are required");
        }
        if (request.tradeDate().isAfter(LocalDate.now(clock.withZone(zone)))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "FUTURE_TRADE_DATE_NOT_ALLOWED",
                    "Trade date cannot be in the future");
        }
        if (request.fee() != null && request.fee().signum() < 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", "Fee cannot be negative");
        }
        switch (request.type()) {
            case BUY, SELL -> {
                if (request.quantity() == null || request.quantity().signum() <= 0 || request.unitPrice() == null
                        || request.unitPrice().signum() < 0) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                            "BUY and SELL require a positive quantity and non-negative unit price");
                }
                if (request.amount() != null) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                            "BUY and SELL do not accept amount");
                }
            }
            case DIVIDEND, FEE -> {
                if (request.amount() == null || request.amount().signum() < 0) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                            "DIVIDEND and FEE require a non-negative amount");
                }
                rejectFeeField(request);
            }
            case DEPOSIT, WITHDRAWAL, INTEREST -> {
                if (request.amount() == null || request.amount().signum() <= 0) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                            request.type() + " requires a positive amount");
                }
                if (request.quantity() != null || request.unitPrice() != null) {
                    throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                            request.type() + " does not accept quantity or unit price");
                }
                rejectFeeField(request);
            }
        }
    }

    private void rejectFeeField(TransactionRequest request) {
        if (request.fee() != null && request.fee().signum() > 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION",
                    request.type() + " must use amount and does not accept the fee field");
        }
    }

    private void recordCsv(int rows, int invalid, int duplicates) {
        ObservabilityMetrics.increment(meterRegistry, ObservabilityMetrics.CSV_ROWS, rows);
        ObservabilityMetrics.increment(meterRegistry, ObservabilityMetrics.CSV_INVALID, invalid);
        ObservabilityMetrics.increment(meterRegistry, ObservabilityMetrics.CSV_DUPLICATE, duplicates);
    }

    private TransactionResponse toResponse(TransactionEntity entity) {
        InstrumentEntity instrument = entity.getInstrument();
        return new TransactionResponse(entity.getId(), instrument == null ? null : instrument.getSymbol(),
                instrument == null ? null : instrument.getName(), entity.getTransactionType(), entity.getTradeDate(),
                entity.getQuantity(), entity.getUnitPrice(), entity.getAmount(), entity.getFee(), entity.getCurrency(),
                entity.getPlanCycleId(), entity.getContributionType(), entity.getContributionPlanId(), entity.getNotes(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getLedgerOrder());
    }

    private CsvRowRequest rowFromColumns(List<String> headers, List<String> values) {
        Map<String, String> data = new java.util.HashMap<>();
        for (int i = 0; i < headers.size() && i < values.size(); i++) {
            String header = headers.get(i).replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
            data.put(header, values.get(i).trim());
        }
        String planCycleId = first(data, "plancycleid", "plan_cycle_id");
        String contributionType = first(data, "contributiontype", "contribution_type");
        String contributionPlanId = first(data, "contributionplanid", "contribution_plan_id");
        return new CsvRowRequest(data.getOrDefault("date", ""), data.getOrDefault("type", ""),
                data.getOrDefault("symbol", ""), data.get("quantity"), data.get("price"), data.get("fee"),
                data.get("amount"), planCycleId, contributionType, contributionPlanId, data.get("notes"));
    }

    private String first(Map<String, String> data, String first, String second) {
        String value = data.get(first);
        return value == null ? data.get(second) : value;
    }

    private List<String> validateRow(CsvRowRequest row) {
        List<String> errors = new ArrayList<>();
        if (row == null) return List.of("row is required");
        LocalDate tradeDate = parseDate(row.date());
        if (tradeDate == null) errors.add("date must be YYYY-MM-DD");
        else if (tradeDate.isAfter(LocalDate.now(clock.withZone(zone)))) errors.add("trade date cannot be in the future");

        TransactionType type = parseType(row.type());
        if (type == null) {
            errors.add("type must be BUY, SELL, DIVIDEND, FEE, DEPOSIT, WITHDRAWAL or INTEREST");
        }

        InstrumentEntity instrument = null;
        String symbol = row.symbol() == null ? "" : row.symbol().trim();
        if (type != null && type.requiresInstrument()) {
            if (symbol.isBlank()) errors.add("symbol is required for " + type);
            else {
                instrument = instrumentRepository.findBySymbolIgnoreCase(symbol).orElse(null);
                if (instrument == null) errors.add("symbol is not tracked");
            }
        } else if (type != null && type.allowsOptionalInstrument()) {
            if (!symbol.isBlank()) {
                instrument = instrumentRepository.findBySymbolIgnoreCase(symbol).orElse(null);
                if (instrument == null) errors.add("symbol is not tracked");
            }
        } else if (type != null && !symbol.isBlank()) {
            errors.add("symbol is not valid for account cash events");
        }

        if (type == TransactionType.BUY || type == TransactionType.SELL) {
            if (decimal(row.quantity()) == null || decimal(row.quantity()).signum() <= 0) errors.add("quantity is required");
            if (decimal(row.price()) == null || decimal(row.price()).signum() < 0) errors.add("price is required");
            if (row.amount() != null && !row.amount().isBlank()) errors.add("amount is not valid for BUY/SELL");
        } else if (type == TransactionType.DIVIDEND || type == TransactionType.FEE) {
            if (decimal(row.amount()) == null || decimal(row.amount()).signum() < 0) errors.add("amount is required");
        } else if (type == TransactionType.DEPOSIT || type == TransactionType.WITHDRAWAL
                || type == TransactionType.INTEREST) {
            if (decimal(row.amount()) == null || decimal(row.amount()).signum() <= 0) errors.add("positive amount is required");
            if (row.quantity() != null && !row.quantity().isBlank()) errors.add("quantity is not valid for cash events");
            if (row.price() != null && !row.price().isBlank()) errors.add("price is not valid for cash events");
        }

        BigDecimal fee = decimal(row.fee());
        if (row.fee() != null && !row.fee().isBlank() && (fee == null || fee.signum() < 0)) {
            errors.add("fee is invalid");
        } else if (type != null && type != TransactionType.BUY && type != TransactionType.SELL
                && fee != null && fee.signum() > 0) {
            errors.add(type + " must use amount and does not accept the fee field");
        }

        UUID cycleId = parseUuid(row.planCycleId(), "planCycleId", errors);
        if (cycleId != null) {
            if (type != TransactionType.BUY) {
                errors.add("only BUY can be linked to a plan cycle");
            } else if (instrument != null && tradeDate != null) {
                try {
                    planService.validateCycleForTransaction(cycleId, instrument.getId(), type, tradeDate);
                } catch (DomainException exception) {
                    errors.add(exception.getMessage());
                }
            }
        }

        ContributionType contributionType = parseContributionType(row.contributionType(), errors);
        UUID contributionPlanId = parseUuid(row.contributionPlanId(), "contributionPlanId", errors);
        if (type != null && tradeDate != null) {
            validateCsvContribution(type, cycleId, contributionType, contributionPlanId, tradeDate, errors);
        }
        return errors;
    }

    private void validateCsvContribution(TransactionType type, UUID cycleId, ContributionType contributionType,
                                         UUID contributionPlanId, LocalDate tradeDate, List<String> errors) {
        if (type != TransactionType.BUY && type != TransactionType.DEPOSIT) {
            if (contributionType != null || contributionPlanId != null) {
                errors.add("contribution source is only valid for DEPOSIT or BUY");
            }
            return;
        }
        if (type == TransactionType.BUY) {
            if (cycleId != null) {
                if (contributionType != null && contributionType != ContributionType.DCA) {
                    errors.add("a plan-cycle BUY must be classified as DCA");
                }
                if (contributionPlanId != null) errors.add("plan-cycle BUY determines its plan from the cycle");
                return;
            }
            if (contributionType == ContributionType.DCA) {
                errors.add("a DCA BUY must be linked to a plan cycle");
                return;
            }
        } else if (cycleId != null) {
            errors.add("DEPOSIT cannot be linked to a plan cycle");
        }

        if (contributionType == ContributionType.INITIAL) {
            if (contributionPlanId == null) errors.add("INITIAL contribution requires contributionPlanId");
            else {
                try {
                    if (!tradeDate.equals(planService.getEntity(contributionPlanId).getStartDate())) {
                        errors.add("initial capital can only be recorded on the investment plan start date");
                    }
                } catch (DomainException exception) {
                    errors.add(exception.getMessage());
                }
            }
        } else if (contributionType == ContributionType.DCA && type == TransactionType.DEPOSIT) {
            if (contributionPlanId == null) errors.add("DCA deposit requires contributionPlanId");
            else {
                try { planService.getEntity(contributionPlanId); }
                catch (DomainException exception) { errors.add(exception.getMessage()); }
            }
        } else if (contributionType == ContributionType.UNPLANNED && contributionPlanId != null) {
            errors.add("UNPLANNED contribution cannot have contributionPlanId");
        } else if (contributionType == null && contributionPlanId != null) {
            errors.add("contributionPlanId requires contributionType");
        }
    }

    private ContributionType parseContributionType(String value, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try { return ContributionType.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            errors.add("contributionType must be INITIAL, DCA or UNPLANNED");
            return null;
        }
    }

    private UUID parseUuid(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()); }
        catch (IllegalArgumentException exception) {
            errors.add(field + " must be a UUID");
            return null;
        }
    }

    private TransactionRequest requestToTransaction(CsvRowRequest row) {
        TransactionType type = TransactionType.valueOf(row.type().trim().toUpperCase(Locale.ROOT));
        UUID cycleId = row.planCycleId() == null || row.planCycleId().isBlank()
                ? null : UUID.fromString(row.planCycleId().trim());
        ContributionType contributionType = row.contributionType() == null || row.contributionType().isBlank()
                ? null : ContributionType.valueOf(row.contributionType().trim().toUpperCase(Locale.ROOT));
        UUID contributionPlanId = row.contributionPlanId() == null || row.contributionPlanId().isBlank()
                ? null : UUID.fromString(row.contributionPlanId().trim());
        return new TransactionRequest(row.symbol(), type, LocalDate.parse(row.date()), decimal(row.quantity()),
                decimal(row.price()), decimal(row.amount()), decimal(row.fee()), cycleId,
                contributionType, contributionPlanId, row.notes());
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
                row.contributionType() == null ? "" : row.contributionType().trim().toUpperCase(Locale.ROOT),
                row.contributionPlanId() == null ? "" : row.contributionPlanId().trim().toLowerCase(Locale.ROOT),
                row.notes() == null ? "" : row.notes().trim());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String canonicalDecimal(String value) {
        if (value == null || value.isBlank()) return "";
        BigDecimal parsed = decimal(value);
        return parsed == null ? value.trim() : parsed.stripTrailingZeros().toPlainString();
    }

    private void ensureCsvFileSize(MultipartFile file) {
        if (file == null) throw invalidCsv("CSV file is required");
        if (file.getSize() > maxCsvBytes) {
            throw new DomainException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV_FILE_TOO_LARGE",
                    "CSV file exceeds the maximum upload size of " + maxCsvBytes + " bytes");
        }
    }

    private List<String> csvFieldLengthErrors(List<String> headers, List<String> values) {
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value != null && value.length() > maxCsvFieldLength) {
                errors.add(csvFieldLengthMessage(csvFieldName(headers, index)));
            }
        }
        return errors;
    }

    private List<String> csvFieldLengthErrors(CsvRowRequest row) {
        List<String> errors = new ArrayList<>();
        if (row == null) return errors;
        addCsvFieldLengthError(errors, "date", row.date());
        addCsvFieldLengthError(errors, "type", row.type());
        addCsvFieldLengthError(errors, "symbol", row.symbol());
        addCsvFieldLengthError(errors, "quantity", row.quantity());
        addCsvFieldLengthError(errors, "price", row.price());
        addCsvFieldLengthError(errors, "fee", row.fee());
        addCsvFieldLengthError(errors, "amount", row.amount());
        addCsvFieldLengthError(errors, "planCycleId", row.planCycleId());
        addCsvFieldLengthError(errors, "contributionType", row.contributionType());
        addCsvFieldLengthError(errors, "contributionPlanId", row.contributionPlanId());
        addCsvFieldLengthError(errors, "notes", row.notes());
        return errors;
    }

    private void addCsvFieldLengthError(List<String> errors, String field, String value) {
        if (value != null && value.length() > maxCsvFieldLength) errors.add(csvFieldLengthMessage(field));
    }

    private String csvFieldName(List<String> headers, int index) {
        if (index >= headers.size()) return "column " + (index + 1);
        String normalized = headers.get(index).replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
        return KNOWN_CSV_FIELDS.contains(normalized) ? normalized : "column " + (index + 1);
    }

    private DomainException csvTooManyRows() {
        return new DomainException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV_TOO_MANY_ROWS",
                "CSV exceeds the maximum of " + maxCsvRows + " data rows");
    }

    private DomainException csvFieldTooLong(int rowNumber, List<String> errors) {
        return new DomainException(HttpStatus.BAD_REQUEST, "CSV_FIELD_TOO_LONG",
                "CSV row " + rowNumber + " has an oversized field: " + String.join(", ", errors));
    }

    private List<String> mergeErrors(List<String> first, List<String> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        List<String> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
    }

    private String csvFieldLengthMessage(String field) {
        return "CSV field " + field + " exceeds maximum field length of " + maxCsvFieldLength;
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

    private record ContributionSource(ContributionType type, UUID planId) { }
}
