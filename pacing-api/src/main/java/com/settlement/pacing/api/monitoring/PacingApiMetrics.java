package com.settlement.pacing.api.monitoring;

import com.settlement.pacing.api.gateway.ReservationExecutionStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import com.settlement.pacing.core.pacing.DecisionReason;
import com.settlement.pacing.core.pacing.DecisionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PacingApiMetrics {
    private static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry meterRegistry;

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
}
