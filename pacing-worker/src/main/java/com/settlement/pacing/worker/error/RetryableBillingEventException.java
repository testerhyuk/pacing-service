package com.settlement.pacing.worker.error;

public class RetryableBillingEventException
        extends RuntimeException {

    public RetryableBillingEventException(String message) {
        super(message);
    }

    public RetryableBillingEventException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
