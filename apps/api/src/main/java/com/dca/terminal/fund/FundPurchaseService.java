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
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.dca.terminal.fund.FundDtos.FundPurchaseRequest;
import static com.dca.terminal.fund.FundDtos.FundPurchaseResponse;

@Service
public class FundPurchaseService {
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

    @Transactional(readOnly = true)
    public List<FundPurchaseResponse> list(UUID fundId) {
        fundService.profile(fundId);
        return purchaseRepository.findAllByInstrumentIdOrderByPurchaseDateAscCreatedAtAscIdAsc(fundId)
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public List<FundPurchaseResponse> listAll() {
        return purchaseRepository.findAllByOrderByPurchaseDateAscCreatedAtAscIdAsc()
                .stream().map(this::response).toList();
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
        if (date.isAfter(LocalDate.now(clock))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "FUND_PURCHASE_DATE_IN_FUTURE",
                    "Fund purchase date cannot be in the future");
        }
        FundNavDailyEntity nav = navRepository.findFirstByInstrumentIdAndNavDateOrderByRetrievedAtDesc(
                        purchase.getInstrument().getId(), date)
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "FUND_PURCHASE_NAV_MISSING",
                        "No exact NAV is stored for the purchase date; sync or add that NAV first"));

        BigDecimal gross = request.grossAmount();
        BigDecimal feeRate = request.purchaseFeeRate();
        BigDecimal net = gross.divide(BigDecimal.ONE.add(feeRate, DecimalMath.MC), DecimalMath.MC);
        BigDecimal fee = gross.subtract(net, DecimalMath.MC);
        BigDecimal shares = net.divide(nav.getNav(), DecimalMath.MC)
                .setScale(DecimalMath.QUANTITY_SCALE, RoundingMode.HALF_UP);

        purchase.setPurchaseDate(date);
        purchase.setGrossAmount(DecimalMath.money(gross));
        purchase.setPurchaseFeeRate(feeRate);
        purchase.setNav(nav.getNav());
        purchase.setPurchaseFee(DecimalMath.money(fee));
        purchase.setNetSubscribedAmount(DecimalMath.money(net));
        purchase.setShares(shares);
        purchase.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
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
