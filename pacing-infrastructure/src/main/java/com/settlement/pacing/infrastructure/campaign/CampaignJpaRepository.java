package com.settlement.pacing.infrastructure.campaign;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignJpaRepository
        extends JpaRepository<CampaignEntity, String> {
}
