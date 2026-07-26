package com.settlement.pacing.worker.billing.application;

import com.settlement.pacing.core.billing.BillingEvent;

public interface BillingEventProcessingGateway {

    BillingEventProcessingResult process(BillingEvent event);

    void markDeadLetter(String eventId, String reason);
}
