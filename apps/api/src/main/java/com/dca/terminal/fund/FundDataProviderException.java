package com.dca.terminal.fund;

public class FundDataProviderException extends RuntimeException {
    public FundDataProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public FundDataProviderException(String message) {
        super(message);
    }
}
