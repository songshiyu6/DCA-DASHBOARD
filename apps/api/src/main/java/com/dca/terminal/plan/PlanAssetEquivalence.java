package com.dca.terminal.plan;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.instrument.InstrumentEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** Explicit positive Nasdaq-100 exposure relationships; never changes the actual ledger. */
public final class PlanAssetEquivalence {
    private static final Map<String, Integer> NASDAQ = Map.of("QQQ", 1, "QQQM", 1, "QLD", 2, "TQQQ", 3);

    private PlanAssetEquivalence() { }

    public record Match(InstrumentEntity asset, BigDecimal multiplier) { }

    public static Optional<Match> resolve(InstrumentEntity instrument, List<InstrumentEntity> assets) {
        if (instrument == null) return Optional.empty();
        var exact = assets.stream().filter(asset -> asset.getId().equals(instrument.getId())).findFirst();
        if (exact.isPresent()) return Optional.of(new Match(exact.get(), BigDecimal.ONE));
        Integer leverage = NASDAQ.get(symbol(instrument));
        if (leverage == null) return Optional.empty();
        // Alternate executions map to an unleveraged plan sleeve. If both QQQ and QQQM
        // are separate targets, require an explicit choice rather than counting twice.
        var targets = assets.stream().filter(asset -> Integer.valueOf(1).equals(NASDAQ.get(symbol(asset)))).toList();
        if (targets.size() > 1) throw new DomainException(HttpStatus.BAD_REQUEST, "AMBIGUOUS_PLAN_EXPOSURE",
                "Plan contains multiple Nasdaq-100 targets; keep one target to link an equivalent ETF");
        return targets.isEmpty() ? Optional.empty() : Optional.of(new Match(targets.getFirst(), BigDecimal.valueOf(leverage)));
    }

    private static String symbol(InstrumentEntity instrument) {
        return instrument.getSymbol() == null ? "" : instrument.getSymbol().trim().toUpperCase(Locale.ROOT);
    }
}
