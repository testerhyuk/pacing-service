package com.settlement.pacing.infrastructure.campaign;

import com.settlement.pacing.core.campaign.Campaign;
import org.springframework.dao.DataIntegrityViolationException;

public class CampaignMapper {

    public Campaign toDomain(CampaignEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException(
                    "CampaignEntity는 null일 수 없습니다"
            );
        }

        try {
            return new Campaign(
                    entity.getCampaignId(),
                    entity.getStatus(),
                    entity.getStartAt(),
                    entity.getEndAt(),
                    entity.getPacingStrategy()
            );
        } catch (IllegalArgumentException exception) {
            throw new DataIntegrityViolationException(
                    "저장된 캠페인 데이터가 올바르지 않습니다: "
                            + entity.getCampaignId(),
                    exception
            );
        }
    }
}
