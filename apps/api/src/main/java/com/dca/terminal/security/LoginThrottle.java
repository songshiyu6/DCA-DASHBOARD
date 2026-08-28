package com.dca.terminal.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class LoginThrottle {
    private final Clock clock;
    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentMap<String, AttemptWindow> windows = new ConcurrentHashMap<>();

    public LoginThrottle(Clock clock,
                         @Value("${dca.security.login-throttle.max-attempts:5}") int maxAttempts,
                         @Value("${dca.security.login-throttle.window-seconds:900}") long windowSeconds) {
        if (maxAttempts < 1) throw new IllegalArgumentException("Login throttle max attempts must be positive");
        if (windowSeconds < 1) throw new IllegalArgumentException("Login throttle window must be positive");
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    boolean allowAttempt(String username, String remoteAddress) {
        Instant now = clock.instant();
        evictExpired(now);
        AtomicBoolean allowed = new AtomicBoolean();
        windows.compute(key(username, remoteAddress), (ignored, current) -> {
            if (current == null || !now.isBefore(current.startedAt().plus(window))) {
                allowed.set(true);
                return new AttemptWindow(now, 1);
            }
            if (current.attempts() >= maxAttempts) {
                allowed.set(false);
                return current;
            }
            allowed.set(true);
            return new AttemptWindow(current.startedAt(), current.attempts() + 1);
        });
        return allowed.get();
    }

    void successfulLogin(String username, String remoteAddress) {
        windows.remove(key(username, remoteAddress));
    }

    int trackedWindows() {
        return windows.size();
    }

    private void evictExpired(Instant now) {
        windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().startedAt().plus(window)));
    }

    private String key(String username, String remoteAddress) {
        return normalize(remoteAddress) + "\u0000" + normalize(username);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record AttemptWindow(Instant startedAt, int attempts) { }
}
