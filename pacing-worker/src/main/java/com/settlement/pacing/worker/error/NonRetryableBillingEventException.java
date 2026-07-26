package com.settlement.pacing.worker.error;

public class NonRetryableBillingEventException
        extends RuntimeException {

    public NonRetryableBillingEventException(String message) {
        super(message);
    }

    public NonRetryableBillingEventException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
