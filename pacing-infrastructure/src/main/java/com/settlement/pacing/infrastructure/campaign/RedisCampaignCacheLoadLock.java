package com.settlement.pacing.infrastructure.campaign;

import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RedisCampaignCacheLoadLock {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisInfrastructureProperties properties;
    private final RedisScript<Long> releaseLockScript;

    public RedisCampaignCacheLoadLock(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisInfrastructureProperties properties,
            RedisScript<Long> releaseLockScript
    ) {
        if (redisTemplate == null
                || keyFactory == null
                || properties == null
                || releaseLockScript == null) {
            throw new IllegalArgumentException(
                    "캠페인 캐시 Lock 구성 값은 null일 수 없습니다"
            );
        }

        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.properties = properties;
        this.releaseLockScript = releaseLockScript;
    }

    /**
     * 캠페인 캐시를 채울 권한을 획득한다.
     */
    public Optional<String> tryAcquire(String campaignId) {
        String token = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(
                        keyFactory.campaignCacheLoadLock(campaignId),
                        token,
                        properties.campaignCacheLoadLockTtl()
                );

        return Boolean.TRUE.equals(acquired)
                ? Optional.of(token)
                : Optional.empty();
    }

    /**
     * 자신이 획득한 Lock만 해제한다.
     */
    public void release(
            String campaignId,
            String token
    ) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "캠페인 캐시 Lock token은 비어있을 수 없습니다"
            );
        }

        redisTemplate.execute(
                releaseLockScript,
                List.of(
                        keyFactory.campaignCacheLoadLock(campaignId)
                ),
                token
        );
    }
}