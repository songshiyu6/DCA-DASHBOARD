package com.dca.terminal.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.Locale;
import java.util.Set;

public final class ObservabilityMetrics {
    public static final String PROVIDER_REQUEST = "dca.provider.request";
    public static final String MARKET_SYNC_ROWS = "dca.market.sync.rows";
    public static final String MARKET_SYNC_SPLITS = "dca.market.sync.splits";
    public static final String SNAPSHOT_INVALIDATE = "dca.snapshot.invalidate";
    public static final String SNAPSHOT_REBUILD = "dca.snapshot.rebuild";
    public static final String PORTFOLIO_REPLAY = "dca.portfolio.replay";
    public static final String PORTFOLIO_REPLAY_TRANSACTIONS = "dca.portfolio.replay.transactions";
    public static final String CSV_ROWS = "dca.csv.rows";
    public static final String CSV_INVALID = "dca.csv.invalid";
    public static final String CSV_DUPLICATE = "dca.csv.duplicate";

    static final Set<String> ALLOWED_TAG_KEYS = Set.of("provider", "operation", "outcome", "status", "mode");
    static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "symbol", "ticker", "notes", "password", "credentials", "sql", "key", "apikey", "api_key",
            "secret", "token", "authorization");
    private static final MeterRegistry NOOP = new CompositeMeterRegistry();

    private ObservabilityMetrics() { }

    public static MeterRegistry noop() {
        return NOOP;
    }

    public static Tags tags(String... keyValues) {
        Tags tags = Tags.of(keyValues);
        validate(tags);
        return tags;
    }

    public static void validate(Iterable<Tag> tags) {
        if (tags == null) return;
        for (Tag tag : tags) {
            String key = tag.getKey();
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_TAG_KEYS.contains(normalized) || "symbol".equals(normalized)) {
                throw new IllegalArgumentException("Forbidden metric tag: " + key);
            }
            if (!ALLOWED_TAG_KEYS.contains(key)) {
                throw new IllegalArgumentException("High-cardinality or unapproved metric tag: " + key);
            }
            rejectSensitiveValue(tag.getValue());
        }
    }

    public static Timer.Sample start(MeterRegistry registry) {
        return Timer.start(registry(registry));
    }

    public static void stop(MeterRegistry registry, Timer.Sample sample, String name, String... keyValues) {
        if (sample == null) return;
        sample.stop(registry(registry).timer(name, tags(keyValues)));
    }

    public static void increment(MeterRegistry registry, String name, double amount, String... keyValues) {
        registry(registry).counter(name, tags(keyValues)).increment(amount);
    }

    public static void record(MeterRegistry registry, String name, double amount, String... keyValues) {
        registry(registry).summary(name, tags(keyValues)).record(amount);
    }

    private static void rejectSensitiveValue(String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("password") || normalized.contains("secret") || normalized.contains("api_key")
                || normalized.contains("apikey") || normalized.contains("bearer ") || normalized.contains("select ")
                || normalized.contains("insert ") || normalized.contains("update ") || normalized.contains("delete ")) {
            throw new IllegalArgumentException("Sensitive metric tag value");
        }
    }

    private static MeterRegistry registry(MeterRegistry registry) {
        return registry == null ? NOOP : registry;
    }
}
