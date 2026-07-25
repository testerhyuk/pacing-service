package com.settlement.pacing.infrastructure.audit;

import com.settlement.pacing.api.audit.AuditLogger;

import java.time.Instant;
import java.util.regex.Pattern;

public class AuditLogSanitizer {
    private static final int MAX_VALUE_LENGTH = 32_000;
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(secret(?:_|-)?key|signature|password)"
                    + "\\s*[:=]\\s*"
                    + "(\"[^\"]*\"|'[^']*'|[^,\\s}]+)"
    );

    public SanitizedAuditEvent sanitize(
            AuditLogger.AuditEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "감사 이벤트는 null일 수 없습니다"
            );
        }

        boolean keyChange = event.eventType()
                == AuditLogger.EventType.HMAC_KEY_CHANGE;

        return new SanitizedAuditEvent(
                event.eventType(),
                limit(event.clientId(), 100),
                limit(event.requestId(), 100),
                limit(event.targetId(), 200),
                keyChange
                        ? redactPresence(event.beforeValue())
                        : sanitizeValue(event.beforeValue()),
                keyChange
                        ? redactPresence(event.afterValue())
                        : sanitizeValue(event.afterValue()),
                event.result(),
                limit(event.reason(), 200),
                event.occurredAt()
        );
    }

    private String sanitizeValue(String value) {
        if (value == null) {
            return null;
        }

        String redacted = SENSITIVE_FIELD.matcher(value)
                .replaceAll("$1=[REDACTED]");
        return limit(redacted, MAX_VALUE_LENGTH);
    }

    private String redactPresence(String value) {
        return value == null ? null : "[REDACTED]";
    }

    private String limit(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }

        return value.substring(0, maximumLength);
    }

    public record SanitizedAuditEvent(
            AuditLogger.EventType eventType,
            String clientId,
            String requestId,
            String targetId,
            String beforeValue,
            String afterValue,
            AuditLogger.Result result,
            String reason,
            Instant occurredAt
    ) {
    }
}
