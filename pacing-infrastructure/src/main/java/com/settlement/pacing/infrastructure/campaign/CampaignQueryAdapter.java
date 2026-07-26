package com.settlement.pacing.infrastructure.campaign;

import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.core.campaign.Campaign;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public class CampaignQueryAdapter implements CampaignQueryGateway {
    private final CampaignJpaRepository campaignRepository;
    private final CampaignMapper campaignMapper;
    private final RedisCampaignCache campaignCache;

    public CampaignQueryAdapter(
            CampaignJpaRepository campaignRepository,
            CampaignMapper campaignMapper,
            RedisCampaignCache campaignCache
    ) {
        this.campaignRepository = campaignRepository;
        this.campaignMapper = campaignMapper;
        this.campaignCache = campaignCache;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Campaign> findById(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "campaignId는 null이거나 비어있을 수 없습니다"
            );
        }

        Optional<Campaign> cached =
                campaignCache.find(campaignId);

        if (cached.isPresent()) {
            return cached;
        }

        return campaignRepository.findById(campaignId)
                .map(campaignMapper::toDomain)
                .map(campaign -> {
                    campaignCache.put(campaign);
                    return campaign;
                });
    }
}
