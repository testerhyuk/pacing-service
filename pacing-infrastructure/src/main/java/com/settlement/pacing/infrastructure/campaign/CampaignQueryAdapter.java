package com.settlement.pacing.infrastructure.campaign;

import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class CampaignQueryAdapter implements CampaignQueryGateway {
    private final CampaignJpaRepository campaignRepository;
    private final CampaignMapper campaignMapper;
    private final RedisCampaignCache campaignCache;
    private final RedisCampaignCacheLoadLock cacheLoadLock;
    private final RedisInfrastructureProperties properties;

    public CampaignQueryAdapter(
            CampaignJpaRepository campaignRepository,
            CampaignMapper campaignMapper,
            RedisCampaignCache campaignCache,
            RedisCampaignCacheLoadLock cacheLoadLock,
            RedisInfrastructureProperties properties
    ) {
        this.campaignRepository = campaignRepository;
        this.campaignMapper = campaignMapper;
        this.campaignCache = campaignCache;
        this.cacheLoadLock = cacheLoadLock;
        this.properties = properties;
    }

    @Override
    public Optional<Campaign> findById(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "campaignId는 null이거나 비어있을 수 없습니다"
            );
        }

        Optional<Campaign> cached = campaignCache.find(campaignId);

        if (cached.isPresent()) {
            return cached;
        }

        return loadOrWait(campaignId);
    }

    private Optional<Campaign> loadOrWait(String campaignId) {
        long deadline = System.nanoTime()
                + properties.campaignCacheLoadWaitTimeout().toNanos();

        while (true) {
            Optional<String> token =
                    cacheLoadLock.tryAcquire(campaignId);

            if (token.isPresent()) {
                return loadWithLock(
                        campaignId,
                        token.get()
                );
            }

            Optional<Campaign> cached =
                    campaignCache.find(campaignId);

            if (cached.isPresent()) {
                return cached;
            }

            if (System.nanoTime() >= deadline) {
                throw new DataAccessResourceFailureException(
                        "캠페인 캐시 로딩 대기시간을 초과했습니다: "
                                + campaignId
                );
            }

            waitBeforeRetry();
        }
    }

    private Optional<Campaign> loadWithLock(String campaignId, String token) {
        try {
            // 최초 캐시 미스 이후 다른 요청이 이미 캐시를 채웠을 수 있다.
            Optional<Campaign> cached =
                    campaignCache.find(campaignId);

            if (cached.isPresent()) {
                return cached;
            }

            Optional<Campaign> loaded =
                    campaignRepository.findById(campaignId)
                            .map(campaignMapper::toDomain);

            loaded.ifPresent(campaignCache::put);

            return loaded;
        } finally {
            cacheLoadLock.release(campaignId, token);
        }
    }

    private void waitBeforeRetry() {
        try {
            TimeUnit.NANOSECONDS.sleep(
                    properties
                            .campaignCacheLoadRetryInterval()
                            .toNanos()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new DataAccessResourceFailureException(
                    "캠페인 캐시 로딩 대기가 중단됐습니다",
                    exception
            );
        }
    }
}
