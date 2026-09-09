package com.dca.terminal.fund;

import com.dca.terminal.common.DecimalMath;
import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import com.dca.terminal.marketdata.FundNavDailyRepository;
import com.dca.terminal.marketdata.MarketDataEntities.FundNavDailyEntity;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.fund.FundDtos.FundPurchaseRequest;
import static com.dca.terminal.fund.FundDtos.FundPurchaseResponse;

@Service
public class FundPurchaseService {
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final FundService fundService;
    private final FundPurchaseRepository purchaseRepository;
    private final FundNavDailyRepository navRepository;
    private final Clock clock;

    public FundPurchaseService(FundService fundService,
                               FundPurchaseRepository purchaseRepository,
                               FundNavDailyRepository navRepository,
                               Clock clock) {
        this.fundService = fundService;
        this.purchaseRepository = purchaseRepository;
        this.navRepository = navRepository;
        this.clock = clock;
    }

    @Transactional
    public List<FundPurchaseResponse> list(UUID fundId) {
        fundService.profile(fundId);
        List<FundPurchaseEntity> purchases = purchaseRepository
                .findAllByInstrumentIdOrderByPurchaseDateAscCreatedAtAscIdAsc(fundId);
        settlePending(purchases);
        return purchases.stream().map(this::response).toList();
    }

    @Transactional
    public List<FundPurchaseResponse> listAll() {
        List<FundPurchaseEntity> purchases = purchaseRepository.findAllByOrderByPurchaseDateAscCreatedAtAscIdAsc();
        settlePending(purchases);
        return purchases.stream()
                .filter(purchase -> purchase.getNav() != null && purchase.getShares() != null)
                .map(this::response)
                .toList();
    }

    @Transactional
    public FundPurchaseResponse create(UUID fundId, @Valid FundPurchaseRequest request) {
        FundProfileEntity profile = fundService.profile(fundId);
        FundPurchaseEntity purchase = new FundPurchaseEntity();
        purchase.setInstrument(profile.getInstrument());
        apply(purchase, request);
        return response(purchaseRepository.saveAndFlush(purchase));
    }

    @Transactional
    public FundPurchaseResponse update(UUID fundId, UUID purchaseId, @Valid FundPurchaseRequest request) {
        fundService.profile(fundId);
        FundPurchaseEntity purchase = purchase(fundId, purchaseId);
        apply(purchase, request);
        return response(purchaseRepository.saveAndFlush(purchase));
    }

    @Transactional
    public UUID delete(UUID fundId, UUID purchaseId) {
        fundService.profile(fundId);
        FundPurchaseEntity purchase = purchase(fundId, purchaseId);
        purchaseRepository.delete(purchase);
        purchaseRepository.flush();
        return purchaseId;
    }

    private void apply(FundPurchaseEntity purchase, FundPurchaseRequest request) {
        LocalDate date = request.purchaseDate();
        if (date.isAfter(LocalDate.now(clock.withZone(CHINA_ZONE)))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "FUND_PURCHASE_DATE_IN_FUTURE",
                    "Fund purchase date cannot be in the future");
        }

        BigDecimal gross = request.grossAmount();
        BigDecimal feeRate = request.purchaseFeeRate();
        BigDecimal net = gross.divide(BigDecimal.ONE.add(feeRate, DecimalMath.MC), DecimalMath.MC);
        BigDecimal fee = gross.subtract(net, DecimalMath.MC);

        purchase.setPurchaseDate(date);
        purchase.setGrossAmount(DecimalMath.money(gross));
        purchase.setPurchaseFeeRate(feeRate);
        purchase.setPurchaseFee(DecimalMath.money(fee));
        purchase.setNetSubscribedAmount(DecimalMath.money(net));
        purchase.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());

        FundNavDailyEntity nav = navRepository.findFirstByInstrumentIdAndNavDateOrderByRetrievedAtDesc(
                        purchase.getInstrument().getId(), date)
                .orElse(null);
        if (nav == null) {
            purchase.setNav(null);
            purchase.setShares(null);
            return;
        }
        settle(purchase, nav);
    }

    private void settlePending(List<FundPurchaseEntity> purchases) {
        boolean changed = false;
        for (FundPurchaseEntity purchase : purchases) {
            if (purchase.getNav() != null && purchase.getShares() != null) continue;
            FundNavDailyEntity nav = navRepository.findFirstByInstrumentIdAndNavDateOrderByRetrievedAtDesc(
                            purchase.getInstrument().getId(), purchase.getPurchaseDate())
                    .orElse(null);
            if (nav == null) continue;
            settle(purchase, nav);
            purchaseRepository.save(purchase);
            changed = true;
        }
        if (changed) purchaseRepository.flush();
    }

    private void settle(FundPurchaseEntity purchase, FundNavDailyEntity nav) {
        BigDecimal shares = purchase.getNetSubscribedAmount().divide(nav.getNav(), DecimalMath.MC)
                .setScale(DecimalMath.QUANTITY_SCALE, RoundingMode.HALF_UP);
        purchase.setNav(nav.getNav());
        purchase.setShares(shares);
    }

    private FundPurchaseEntity purchase(UUID fundId, UUID purchaseId) {
        return purchaseRepository.findByIdAndInstrumentId(purchaseId, fundId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "FUND_PURCHASE_NOT_FOUND",
                        "Fund purchase not found"));
    }

    private FundPurchaseResponse response(FundPurchaseEntity purchase) {
        InstrumentEntity instrument = purchase.getInstrument();
        return new FundPurchaseResponse(
                purchase.getId(), instrument.getId(), instrument.getSymbol(), instrument.getName(),
                purchase.getPurchaseDate(), purchase.getGrossAmount(), purchase.getPurchaseFeeRate(),
                purchase.getNav(), purchase.getPurchaseFee(), purchase.getNetSubscribedAmount(), purchase.getShares(),
                purchase.getNotes(), purchase.getCreatedAt(), purchase.getUpdatedAt());
    }
}
