package com.settlement.pacing.infrastructure.worker;

public record RedisExpirationTransition(
        Status status,
        String reservationId,
        long reservationVersion
) {
    public RedisExpirationTransition {
        if (status == null || reservationVersion < -1) {
            throw new IllegalArgumentException(
                    "Redis 예약 만료 결과가 올바르지 않습니다"
            );
        }
    }

    public enum Status {
        EXPIRED,
        SKIPPED,
        CONFLICT
    }
}
