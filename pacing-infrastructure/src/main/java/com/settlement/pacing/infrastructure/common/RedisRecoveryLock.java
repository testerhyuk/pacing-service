package com.settlement.pacing.infrastructure.common;

import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RedisRecoveryLock {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisInfrastructureProperties properties;
    private final RedisScript<Long> releaseLockScript;

    public RedisRecoveryLock(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisInfrastructureProperties properties,
            RedisScript<Long> releaseLockScript
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.properties = properties;
        this.releaseLockScript = releaseLockScript;
    }

    public Optional<String> tryAcquire(String campaignId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(
                        keyFactory.recoveryLock(campaignId),
                        token,
                        properties.recoveryLockTtl()
                );

        return Boolean.TRUE.equals(acquired)
                ? Optional.of(token)
                : Optional.empty();
    }

    public void release(
            String campaignId,
            String token
    ) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "복구 Lock Token은 비어있을 수 없습니다"
            );
        }

        redisTemplate.execute(
                releaseLockScript,
                List.of(keyFactory.recoveryLock(campaignId)),
                token
        );
    }
}
