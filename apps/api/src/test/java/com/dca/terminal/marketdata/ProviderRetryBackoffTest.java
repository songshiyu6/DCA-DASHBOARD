package com.dca.terminal.marketdata;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderRetryBackoffTest {
    @Test
    void growsExponentiallyAndCapsDelay() {
        assertEquals(Duration.ofMillis(50), ProviderRetryBackoff.delayAfterAttempt(1));
        assertEquals(Duration.ofMillis(100), ProviderRetryBackoff.delayAfterAttempt(2));
        assertEquals(Duration.ofMillis(200), ProviderRetryBackoff.delayAfterAttempt(3));
        assertEquals(Duration.ofMillis(400), ProviderRetryBackoff.delayAfterAttempt(4));
        assertEquals(Duration.ofMillis(500), ProviderRetryBackoff.delayAfterAttempt(5));
        assertEquals(Duration.ofMillis(500), ProviderRetryBackoff.delayAfterAttempt(20));
    }

    @Test
    void nonPositiveAttemptHasNoDelay() {
        assertEquals(Duration.ZERO, ProviderRetryBackoff.delayAfterAttempt(0));
        assertEquals(Duration.ZERO, ProviderRetryBackoff.delayAfterAttempt(-1));
    }
}
