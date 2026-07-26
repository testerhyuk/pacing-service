package com.settlement.pacing.worker.expiration.application;

public record ExpirationBatchResult(
        int candidates,
        int expired,
        int skipped,
        int conflicts
) {
    public ExpirationBatchResult {
        if (candidates < 0
                || expired < 0
                || skipped < 0
                || conflicts < 0) {
            throw new IllegalArgumentException(
                    "예약 만료 처리 건수는 음수일 수 없습니다"
            );
        }

        if (expired + skipped + conflicts > candidates) {
            throw new IllegalArgumentException(
                    "예약 만료 처리 결과 합계가 후보 수보다 클 수 없습니다"
            );
        }
    }

    public static ExpirationBatchResult empty() {
        return new ExpirationBatchResult(0, 0, 0, 0);
    }
}
