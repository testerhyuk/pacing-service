package com.settlement.pacing.api.monitoring;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.gateway.ReservationExecutionStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import com.settlement.pacing.core.pacing.DecisionReason;
import com.settlement.pacing.core.pacing.DecisionType;
import com.settlement.pacing.core.pacing.PacingObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
public class PacingApiMetrics {
    private static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<PacingStrategy, RateUpdateMeters>
            rateUpdateMeters = new ConcurrentHashMap<>();

    private final PacingProperties pacingProperties;

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordPacingDecision(
            Timer.Sample sample,
            PacingStrategy strategy,
            DecisionType decision,
            DecisionReason reason
    ) {
        sample.stop(
                Timer.builder("pacing.api.decision")
                        .description("페이싱 판단 처리 시간")
                        .tag("strategy", strategy.name())
                        .tag("decision", decision.name())
                        .tag("reason", reason.name())
                        .tag("outcome", "SUCCESS")
                        .register(meterRegistry)
        );
    }

    public void recordPacingDecisionFailure(
            Timer.Sample sample,
            RuntimeException exception
    ) {
        sample.stop(
                Timer.builder("pacing.api.decision")
                        .description("페이싱 판단 처리 시간")
                        .tag("strategy", UNKNOWN)
                        .tag("decision", "ERROR")
                        .tag("reason", exceptionName(exception))
                        .tag("outcome", "FAILURE")
                        .register(meterRegistry)
        );
    }

    public void recordPacingStateConflict() {
        Counter.builder("pacing.api.state.conflict")
                .description("페이싱 상태 CAS 충돌 횟수")
                .register(meterRegistry)
                .increment();
    }

    public void recordPacingRateUpdate(
            PacingStrategy strategy,
            PacingObservation observation,
            double pacingRate,
            Duration updateInterval
    ) {
        if (strategy == null
                || observation == null
                || updateInterval == null
                || updateInterval.isZero()
                || updateInterval.isNegative()) {
            return;
        }

        double intervalSeconds = updateInterval.toMillis() / 1_000.0;

        if (intervalSeconds <= 0.0) {
            return;
        }

        RateUpdateMeters meters = rateUpdateMeters.computeIfAbsent(
                strategy,
                this::registerRateUpdateMeters
        );

        int intervalCount = observation.intervalCount();

        double estimatedDecisionCountPerInterval =
                observation.estimatedDecisionCountPerInterval(pacingProperties.ewmaAlpha());

        double decisionRate =
                estimatedDecisionCountPerInterval / intervalSeconds;
        double passRate = observation.decisionCount() == 0L
                ? 0.0
                : (double) observation.passCount()
                        / observation.decisionCount();
        double reservedAmountPerInterval = intervalCount == 0
                ? 0.0
                : (double) observation.reservedAmount().amount()
                        / intervalCount;

        meters.pacingRate().set(pacingRate);
        meters.decisionRate().set(decisionRate);
        meters.passRate().set(passRate);
        meters.intervalCount().set((double) intervalCount);
        meters.reservedAmountPerInterval().set(
                reservedAmountPerInterval
        );
        meters.fullPassAmountPerInterval().set(
                observation.estimatedFullPassAmountPerInterval(pacingProperties.ewmaAlpha())
        );
        meters.updateCounter().increment();
    }

    public void recordPacingReservation(
            Timer.Sample sample,
            ReservationExecutionStatus status
    ) {
        sample.stop(
                Timer.builder("pacing.api.reservation")
                        .description("예산 예약 처리 시간")
                        .tag("status", status.name())
                        .tag("reason", "NONE")
                        .tag(
                                "outcome",
                                isSuccessfulReservation(status)
                                        ? "SUCCESS"
                                        : "FAILURE"
                        )
                        .register(meterRegistry)
        );
    }

    public void recordPacingReservationFailure(
            Timer.Sample sample,
            RuntimeException exception
    ) {
        sample.stop(
                Timer.builder("pacing.api.reservation")
                        .description("예산 예약 처리 시간")
                        .tag("status", "ERROR")
                        .tag("reason", exceptionName(exception))
                        .tag("outcome", "FAILURE")
                        .register(meterRegistry)
        );
    }

    public void recordAuthenticationFailure(String reason) {
        Counter.builder("pacing.api.authentication.failure")
                .description("서비스 인증 실패 횟수")
                .tag("reason", tagValue(reason))
                .register(meterRegistry)
                .increment();
    }

    public void recordRateLimitRejection(String clientId) {
        Counter.builder("pacing.api.rate_limit.rejection")
                .description("클라이언트별 Rate Limit 거절 횟수")
                .tag("clientId", tagValue(clientId))
                .register(meterRegistry)
                .increment();
    }

    private String exceptionName(RuntimeException exception) {
        return exception == null
                ? UNKNOWN
                : exception.getClass().getSimpleName();
    }

    private boolean isSuccessfulReservation(
            ReservationExecutionStatus status
    ) {
        return status == ReservationExecutionStatus.CREATED
                || status == ReservationExecutionStatus.ALREADY_EXISTS;
    }

    private String tagValue(String value) {
        return value == null || value.isBlank()
                ? UNKNOWN
                : value;
    }

    private RateUpdateMeters registerRateUpdateMeters(
            PacingStrategy strategy
    ) {
        String strategyName = strategy.name();

        return new RateUpdateMeters(
                gauge(
                        "pacing.api.rate_update.pacing_rate",
                        "Latest PASS rate applied during a pacing refresh",
                        strategyName
                ),
                gauge(
                        "pacing.api.rate_update.decision_rate",
                        "Decision requests per second seen in the observation window",
                        strategyName
                ),
                gauge(
                        "pacing.api.rate_update.pass_rate",
                        "PASS ratio seen in the observation window",
                        strategyName
                ),
                gauge(
                        "pacing.api.rate_update.interval_count",
                        "Number of populated intervals in the observation window",
                        strategyName
                ),
                gauge(
                        "pacing.api.rate_update.reserved_amount_per_interval",
                        "Reserved amount per observed interval",
                        strategyName
                ),
                gauge(
                        "pacing.api.rate_update.full_pass_amount_per_interval",
                        "Estimated reservation amount if every observed decision passed",
                        strategyName
                ),
                Counter.builder("pacing.api.rate_update")
                        .description("Pacing rate refresh count")
                        .tag("strategy", strategyName)
                        .register(meterRegistry)
        );
    }

    private AtomicReference<Double> gauge(
            String name,
            String description,
            String strategy
    ) {
        AtomicReference<Double> value = new AtomicReference<>(0.0);

        Gauge.builder(name, value, current -> current.get())
                .description(description)
                .tag("strategy", strategy)
                .register(meterRegistry);

        return value;
    }

    private record RateUpdateMeters(
            AtomicReference<Double> pacingRate,
            AtomicReference<Double> decisionRate,
            AtomicReference<Double> passRate,
            AtomicReference<Double> intervalCount,
            AtomicReference<Double> reservedAmountPerInterval,
            AtomicReference<Double> fullPassAmountPerInterval,
            Counter updateCounter
    ) {
    }
}
