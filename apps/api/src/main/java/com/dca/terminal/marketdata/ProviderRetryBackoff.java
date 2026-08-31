package com.dca.terminal.marketdata;

import java.time.Duration;

final class ProviderRetryBackoff {
    private static final long BASE_MILLIS = 50L;
    private static final long MAX_MILLIS = 500L;

    private ProviderRetryBackoff() { }

    static Duration delayAfterAttempt(int completedAttempt) {
        if (completedAttempt <= 0) return Duration.ZERO;
        int shift = Math.min(completedAttempt - 1, 20);
        long multiplier = 1L << shift;
        long millis;
        try {
            millis = Math.multiplyExact(BASE_MILLIS, multiplier);
        } catch (ArithmeticException ignored) {
            millis = MAX_MILLIS;
        }
        return Duration.ofMillis(Math.min(millis, MAX_MILLIS));
    }

    static void pause(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
