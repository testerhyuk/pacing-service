package com.settlement.pacing.infrastructure.audit;

import com.settlement.pacing.api.audit.AuditLogger;

import java.time.Clock;

public class PostgresAuditLogger implements AuditLogger {
    private final AuditLogJpaRepository repository;
    private final AuditLogSanitizer sanitizer;
    private final Clock clock;

    public PostgresAuditLogger(
            AuditLogJpaRepository repository,
            AuditLogSanitizer sanitizer,
            Clock clock
    ) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.clock = clock;
    }

    @Override
    public void log(AuditEvent event) {
        AuditLogSanitizer.SanitizedAuditEvent sanitized =
                sanitizer.sanitize(event);

        repository.save(new AuditLogEntity(
                sanitized.eventType(),
                sanitized.clientId(),
                sanitized.requestId(),
                sanitized.targetId(),
                sanitized.beforeValue(),
                sanitized.afterValue(),
                sanitized.result(),
                sanitized.reason(),
                sanitized.occurredAt(),
                clock.instant()
        ));
    }
}
