package com.settlement.pacing.infrastructure.audit;

import org.springframework.data.repository.Repository;

public interface AuditLogJpaRepository
        extends Repository<AuditLogEntity, Long> {

    AuditLogEntity save(AuditLogEntity entity);
}
