package com.github.guoyyit.linkagedisequilibriumservicejava.model;

public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    TIMEOUT;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }

    /** A non-successful terminal state after which the same key may recompute. */
    public boolean isTerminalFailure() {
        return this == CANCELLED || this == FAILED || this == TIMEOUT;
    }
}
