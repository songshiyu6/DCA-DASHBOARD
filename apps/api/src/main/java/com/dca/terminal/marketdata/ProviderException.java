package com.dca.terminal.marketdata;

public class ProviderException extends RuntimeException {
    private final ProviderId provider;
    private final boolean retryable;

    public ProviderException(ProviderId provider, String message, boolean retryable) {
        super(message);
        this.provider = provider;
        this.retryable = retryable;
    }

    public ProviderException(ProviderId provider, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.retryable = retryable;
    }

    public ProviderId provider() { return provider; }
    public boolean retryable() { return retryable; }
}
