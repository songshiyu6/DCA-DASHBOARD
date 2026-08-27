package com.dca.terminal.marketdata;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalEtfCatalogTest {
    private final CanonicalEtfCatalog catalog = new CanonicalEtfCatalog();

    @Test
    void containsCanonicalQqqAndVooIdentityRecords() {
        assertEquals(List.of("VOO"), catalog.search("voo").stream()
                .map(ProviderModels.ProviderSearchResult::symbol).toList());
        assertEquals("Invesco QQQ Trust", catalog.findExact("QQQ").orElseThrow().name());
        assertEquals("NASDAQ", catalog.findExact("QQQ").orElseThrow().exchange());
    }

    @Test
    void neverTreatsAnUnknownTickerAsAnEtf() {
        assertTrue(catalog.search("NOT-A-REAL-TICKER").isEmpty());
        assertTrue(catalog.findExact("NOT-A-REAL-TICKER").isEmpty());
    }
}
