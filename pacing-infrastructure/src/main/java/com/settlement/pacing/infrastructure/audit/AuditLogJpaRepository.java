package com.settlement.pacing.infrastructure.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogJpaRepository
        extends JpaRepository<AuditLogEntity, Long> {
}
