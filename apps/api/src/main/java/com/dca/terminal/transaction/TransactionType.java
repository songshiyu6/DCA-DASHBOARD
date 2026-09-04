package com.dca.terminal.transaction;

public enum TransactionType {
    BUY,
    SELL,
    DIVIDEND,
    FEE,
    DEPOSIT,
    WITHDRAWAL,
    INTEREST;

    public boolean requiresInstrument() {
        return this == BUY || this == SELL || this == DIVIDEND;
    }

    public boolean allowsOptionalInstrument() {
        return this == FEE;
    }

    public boolean isExternalCashFlow() {
        return this == DEPOSIT || this == WITHDRAWAL;
    }

    public boolean requiresAmount() {
        return this == DIVIDEND || this == FEE || this == DEPOSIT
                || this == WITHDRAWAL || this == INTEREST;
    }
}
