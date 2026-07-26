package com.settlement.pacing.api.audit;

import java.time.Instant;

public interface AuditLogger {

    /**
     * 보안 및 운영상 중요한 이벤트를 감사 로그로 저장한다.
     */
    void log(AuditEvent event);

    record AuditEvent(
            EventType eventType,
            String clientId,
            String requestId,
            String targetId,
            String beforeValue,
            String afterValue,
            Result result,
            String reason,
            Instant occurredAt
    ) {
        public AuditEvent {
            if (eventType == null
                    || result == null
                    || occurredAt == null) {
                throw new IllegalArgumentException(
                        "감사 이벤트 타입, 결과와 발생 시각은 null일 수 없습니다"
                );
            }
        }
    }

    enum EventType {
        AUTHENTICATION_FAILURE,
        AUTHORIZATION_FAILURE,
        CAMPAIGN_CHANGE,
        BUDGET_CHANGE,
        PACING_STRATEGY_CHANGE,
        PEAK_POLICY_CHANGE,
        HMAC_KEY_CHANGE,
        CLIENT_PERMISSION_CHANGE
    }

    enum Result {
        SUCCESS,
        FAILURE
    }
}
