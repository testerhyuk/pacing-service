package com.settlement.pacing.infrastructure.security;

import com.settlement.pacing.api.security.NonceStore;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class RedisNonceStore implements NonceStore {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final PacingInfrastructureMetrics metrics;

    public RedisNonceStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            PacingInfrastructureMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.metrics = metrics;
    }

    @Override
    public boolean saveIfAbsent(
            String clientId,
            String nonce,
            Duration ttl
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "nonce TTL은 0보다 커야 합니다"
            );
        }

        Boolean saved = redisTemplate.opsForValue().setIfAbsent(
                keyFactory.nonce(clientId, nonce),
                "1",
                ttl
        );

        boolean stored = Boolean.TRUE.equals(saved);
        metrics.recordNonce(stored);
        return stored;
    }
}
