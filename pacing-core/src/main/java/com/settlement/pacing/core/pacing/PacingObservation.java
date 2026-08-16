package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.Money;

import java.util.List;

/**
 * 최근 완료된 페이싱 구간의 캠페인별 관측 결과다.
 *
 * 각 구간의 원본 값을 보존한다. 따라서 최근 구간과 오래된 구간을
 * 서로 다른 가중치로 사용해 현재 traffic을 추정할 수 있다.
 */
public record PacingObservation(
        List<Interval> intervals
) {
    private static final PacingObservation EMPTY =
            new PacingObservation(List.of());

    public PacingObservation {
        if (intervals == null) {
            throw new IllegalArgumentException(
                    "관측 구간 목록은 null일 수 없습니다"
            );
        }

        intervals = List.copyOf(intervals);
    }

    public static PacingObservation empty() {
        return EMPTY;
    }

    /**
     * 관측 구간 하나의 원본 데이터다.
     *
     * intervals는 오래된 구간부터 최신 구간 순서로 저장한다.
     */
    public record Interval(
            long decisionCount,
            long passCount,
            long reservationCount,
            Money reservedAmount
    ) {
        public Interval {
            if (decisionCount < 0L
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
        }
    }

    public int intervalCount() {
        return intervals.size();
    }

    public long decisionCount() {
        return intervals.stream()
                .mapToLong(Interval::decisionCount)
                .sum();
    }

    public long passCount() {
        return intervals.stream()
                .mapToLong(Interval::passCount)
                .sum();
    }

    public long reservationCount() {
        return intervals.stream()
                .mapToLong(Interval::reservationCount)
                .sum();
    }

    public Money reservedAmount() {
        long amount = intervals.stream()
                .mapToLong(interval ->
                        interval.reservedAmount().amount())
                .sum();

        return new Money(amount);
    }

    /**
     * 최근 트래픽이 그대로 유지되고 모든 요청을 PASS했을 때
     * 한 구간에 예약될 것으로 예상되는 금액을 계산한다.
     *
     * traffic은 오래된 구간부터 최신 구간까지 EWMA로 계산한다.
     * 현재 구간의 decisionCount가 클수록 최근 변화가 더 빠르게 반영된다.
     */
    public double estimatedFullPassAmountPerInterval(double ewmaAlpha) {
        if (intervals.isEmpty()
                || decisionCount() == 0L
                || passCount() == 0L
                || reservedAmount().isZero()) {
            return 0.0;
        }

        double estimatedDecisionCountPerInterval =
                estimatedDecisionCountPerInterval(ewmaAlpha);

        double estimatedReservedAmountPerPass =
                estimatedReservedAmountPerPass(ewmaAlpha);

        return estimatedDecisionCountPerInterval
                * estimatedReservedAmountPerPass;
    }

    public double estimatedReservedAmountPerPass(double ewmaAlpha) {
        double ewma = 0.0;
        boolean initialized = false;

        for (Interval interval : intervals) {
            if (interval.passCount() == 0L) {
                continue;
            }

            double amountPerPass =
                    (double) interval.reservedAmount().amount()
                            / interval.passCount();

            if (!initialized) {
                ewma = amountPerPass;
                initialized = true;
                continue;
            }

            ewma = amountPerPass * ewmaAlpha
                    + ewma * (1.0 - ewmaAlpha);
        }

        return initialized ? ewma : 0.0;
    }

    public double estimatedDecisionCountPerInterval(double ewmaAlpha) {
        double ewma = 0.0;
        boolean initialized = false;

        for (Interval interval : intervals) {
            double decisionCount = interval.decisionCount();

            if (!initialized) {
                ewma = decisionCount;
                initialized = true;
                continue;
            }

            ewma = decisionCount * ewmaAlpha
                    + ewma * (1.0 - ewmaAlpha);
        }

        return ewma;
    }
}
