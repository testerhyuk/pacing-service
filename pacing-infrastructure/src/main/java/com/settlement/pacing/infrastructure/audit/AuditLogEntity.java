package com.settlement.pacing.infrastructure.audit;

import com.settlement.pacing.api.audit.AuditLogger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private AuditLogger.EventType eventType;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "target_id", length = 200)
    private String targetId;

    @Column(name = "before_value")
    private String beforeValue;

    @Column(name = "after_value")
    private String afterValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private AuditLogger.Result result;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(
            AuditLogger.EventType eventType,
            String clientId,
            String requestId,
            String targetId,
            String beforeValue,
            String afterValue,
            AuditLogger.Result result,
            String reason,
            Instant occurredAt,
            Instant createdAt
    ) {
        this.eventType = eventType;
        this.clientId = clientId;
        this.requestId = requestId;
        this.targetId = targetId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.result = result;
        this.reason = reason;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }
}
