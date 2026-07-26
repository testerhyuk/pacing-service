package com.settlement.pacing.worker.expiration.application;

import java.time.Instant;

public interface ExpiredReservationGateway {

    ExpirationBatchResult expire(
            Instant now,
            int batchSize
    );
}
