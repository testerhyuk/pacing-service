package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.Money;

/**
 * 최근 완료된 페이싱 구간의 캠페인별 집계 결과다.
 */
public record PacingObservation(
        int intervalCount,
        long decisionCount,
        long passCount,
        long reservationCount,
        Money reservedAmount
) {
    private static final PacingObservation EMPTY =
            new PacingObservation(
                    0,
                    0L,
                    0L,
                    0L,
                    Money.zero()
            );

    public PacingObservation {
        if (intervalCount < 0
                || decisionCount < 0L
                || passCount < 0L
                || reservationCount < 0L) {
            throw new IllegalArgumentException(
                    "페이싱 관측값은 음수일 수 없습니다"
            );
        }

        if (reservedAmount == null) {
            throw new IllegalArgumentException(
                    "예약 금액은 null일 수 없습니다"
            );
        }

        if (passCount > decisionCount) {
            throw new IllegalArgumentException(
                    "PASS 건수는 판단 요청 건수를 초과할 수 없습니다"
            );
        }

        if (intervalCount == 0
                && (decisionCount != 0L
                || passCount != 0L
                || reservationCount != 0L
                || !reservedAmount.isZero())) {
            throw new IllegalArgumentException(
                    "관측 구간이 없으면 집계값도 없어야 합니다"
            );
        }
    }

    public static PacingObservation empty() {
        return EMPTY;
    }

    /**
     * 최근 트래픽이 그대로 유지되고 모든 요청을 PASS했을 때
     * 한 구간에 예약될 것으로 예상되는 금액을 계산한다.
     */
    public double estimatedFullPassAmountPerInterval() {
        if (intervalCount == 0
                || decisionCount == 0L
                || passCount == 0L
                || reservedAmount.isZero()) {
            return 0.0;
        }

        double averageDecisionCount =
                (double) decisionCount / intervalCount;
        double reservedAmountPerPass =
                (double) reservedAmount.amount() / passCount;

        return averageDecisionCount * reservedAmountPerPass;
    }
}
