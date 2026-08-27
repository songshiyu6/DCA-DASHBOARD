package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;

/**
 * A small, reviewed identity allowlist used when a remote directory provider
 * is unavailable. It contains no prices, performance, or portfolio facts.
 */
public final class CanonicalEtfCatalog {
    private static final List<ProviderSearchResult> ENTRIES = List.of(
            etf("VOO", "Vanguard S&P 500 ETF", "NYSEArca"),
            etf("QQQ", "Invesco QQQ Trust", "NASDAQ"),
            etf("SCHD", "Schwab US Dividend Equity ETF", "NYSEArca"),
            etf("VTI", "Vanguard Total Stock Market ETF", "NYSEArca"),
            etf("VT", "Vanguard Total World Stock ETF", "NYSEArca"),
            etf("SGOV", "iShares 0-3 Month Treasury Bond ETF", "NASDAQ"),
            etf("AVUV", "Avantis U.S. Small Cap Value ETF", "NYSEArca"));

    public List<ProviderSearchResult> search(String query) {
        String normalized = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) return List.of();
        return ENTRIES.stream()
                .filter(item -> item.symbol().contains(normalized)
                        || item.name().toUpperCase(Locale.ROOT).contains(normalized))
                .limit(20)
                .toList();
    }

    public Optional<ProviderSearchResult> findExact(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return ENTRIES.stream().filter(item -> item.symbol().equalsIgnoreCase(symbol.trim())).findFirst();
    }

    private static ProviderSearchResult etf(String symbol, String name, String exchange) {
        return new ProviderSearchResult(symbol, name, exchange, "USD", InstrumentType.ETF);
    }
}
