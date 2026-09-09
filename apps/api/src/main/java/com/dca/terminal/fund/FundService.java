package com.dca.terminal.fund;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.instrument.InstrumentRepository;
import com.dca.terminal.instrument.InstrumentType;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.fund.FundDtos.FundRequest;
import static com.dca.terminal.fund.FundDtos.FundResponse;
import static com.dca.terminal.fund.FundDtos.NavRequest;
import static com.dca.terminal.fund.FundDtos.NavResponse;

@Service
public class FundService {
    private final InstrumentRepository instrumentRepository;
    private final FundProfileRepository profileRepository;
    private final FundNavDailyRepository navRepository;
    private final AutoDcaRuleRepository ruleRepository;
    private final Clock clock;

    public FundService(InstrumentRepository instrumentRepository,
                       FundProfileRepository profileRepository,
                       FundNavDailyRepository navRepository,
                       AutoDcaRuleRepository ruleRepository,
                       Clock clock) {
        this.instrumentRepository = instrumentRepository;
        this.profileRepository = profileRepository;
        this.navRepository = navRepository;
        this.ruleRepository = ruleRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FundResponse> list() {
        return profileRepository.findAll().stream()
                .map(this::response)
                .sorted(java.util.Comparator.comparing(FundResponse::code))
                .toList();
    }

    @Transactional(readOnly = true)
    public FundResponse get(UUID id) {
        return response(profile(id));
    }

    @Transactional(readOnly = true)
    public FundProfileEntity profileByCode(String code) {
        String normalized = normalizeCode(code);
        InstrumentEntity instrument = instrumentRepository.findBySymbolIgnoreCase(normalized)
                .filter(candidate -> candidate.getInstrumentType() == InstrumentType.MUTUAL_FUND)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "FUND_NOT_FOUND", "Fund not found: " + normalized));
        return profileRepository.findById(instrument.getId())
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "FUND_PROFILE_MISSING",
                        "Mutual fund is missing its fund profile: " + normalized));
    }

    @Transactional
    public FundResponse create(@Valid FundRequest request) {
        String code = normalizeCode(request.code());
        if (instrumentRepository.findBySymbolIgnoreCase(code).isPresent()) {
            throw new DomainException(HttpStatus.CONFLICT, "FUND_CODE_EXISTS", "Instrument code already exists: " + code);
        }
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setSymbol(code);
        instrument.setName(request.name().trim());
        instrument.setExchange("CN_FUND");
        instrument.setCurrency("CNY");
        instrument.setInstrumentType(InstrumentType.MUTUAL_FUND);
        instrument.setDataProvider("MANUAL");
        // Keep the legacy tracked-instrument feed ETF-only until the fund-specific frontend/provider path is wired.
        instrument.setTracked(false);
        instrument = instrumentRepository.saveAndFlush(instrument);

        FundProfileEntity profile = new FundProfileEntity();
        profile.setInstrument(instrument);
        apply(profile, request);
        return response(profileRepository.saveAndFlush(profile));
    }

    @Transactional
    public FundResponse update(UUID id, @Valid FundRequest request) {
        FundProfileEntity profile = profile(id);
        InstrumentEntity instrument = profile.getInstrument();
        String code = normalizeCode(request.code());
        if (!instrument.getSymbol().equalsIgnoreCase(code)) {
            instrumentRepository.findBySymbolIgnoreCase(code).ifPresent(existing -> {
                if (!existing.getId().equals(instrument.getId())) {
                    throw new DomainException(HttpStatus.CONFLICT, "FUND_CODE_EXISTS",
                            "Instrument code already exists: " + code);
                }
            });
            instrument.setSymbol(code);
        }
        instrument.setName(request.name().trim());
        instrumentRepository.save(instrument);
        apply(profile, request);
        return response(profileRepository.saveAndFlush(profile));
    }

    @Transactional
    public UUID delete(UUID id) {
        FundProfileEntity profile = profile(id);
        InstrumentEntity instrument = profile.getInstrument();

        navRepository.deleteAllByInstrumentId(id);
        navRepository.flush();
        ruleRepository.deleteAllByInstrumentId(id);
        ruleRepository.flush();
        profileRepository.delete(profile);
        profileRepository.flush();
        instrumentRepository.delete(instrument);
        instrumentRepository.flush();
        return id;
    }

    @Transactional
    public NavResponse putNav(UUID fundId, @Valid NavRequest request) {
        FundProfileEntity profile = profile(fundId);
        String source = request.source() == null || request.source().isBlank()
                ? "MANUAL" : request.source().trim().toUpperCase(Locale.ROOT);
        FundNavDailyEntity nav = navRepository.findByInstrumentIdAndNavDateAndSource(
                        fundId, request.navDate(), source)
                .orElseGet(FundNavDailyEntity::new);
        nav.setInstrument(profile.getInstrument());
        nav.setNavDate(request.navDate());
        nav.setNav(request.nav());
        nav.setSource(source);
        nav.setRetrievedAt(clock.instant());
        return navResponse(navRepository.saveAndFlush(nav));
    }

    @Transactional(readOnly = true)
    public List<NavResponse> navHistory(UUID fundId) {
        profile(fundId);
        return navRepository.findAllByInstrumentIdOrderByNavDateAscRetrievedAtDesc(fundId).stream()
                .map(this::navResponse).toList();
    }

    @Transactional(readOnly = true)
    public FundProfileEntity profile(UUID id) {
        return profileRepository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "FUND_NOT_FOUND", "Fund not found"));
    }

    private void apply(FundProfileEntity profile, FundRequest request) {
        profile.setManagementFeeRate(request.managementFeeRate() == null ? BigDecimal.ZERO : request.managementFeeRate());
        profile.setConfirmationTradingDays(request.confirmationTradingDays() == null ? 1 : request.confirmationTradingDays());
        profile.setShareClass(request.shareClass() == null || request.shareClass().isBlank()
                ? null : request.shareClass().trim().toUpperCase(Locale.ROOT));
        profile.setCalendarCode("CN_FUND");
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-Z][0-9A-Z.-]{0,15}")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FUND_CODE",
                    "Fund code must contain 1-16 letters, digits, dots or hyphens");
        }
        return normalized;
    }

    private FundResponse response(FundProfileEntity profile) {
        InstrumentEntity instrument = profile.getInstrument();
        return new FundResponse(instrument.getId(), instrument.getSymbol(), instrument.getName(),
                instrument.getCurrency(), profile.getManagementFeeRate(), profile.getConfirmationTradingDays(),
                profile.getShareClass(), profile.getCalendarCode());
    }

    private NavResponse navResponse(FundNavDailyEntity nav) {
        return new NavResponse(nav.getId(), nav.getNavDate(), nav.getNav(), nav.getSource(), nav.getRetrievedAt());
    }
}
