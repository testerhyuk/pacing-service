package com.settlement.pacing.infrastructure.security;

import com.settlement.pacing.api.security.ClientRateLimiter;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.config.RateLimitProperties;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

public class RedisClientRateLimiter
        implements ClientRateLimiter {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RateLimitProperties properties;
    private final RedisScript<Long> tokenBucketScript;
    private final PacingInfrastructureMetrics metrics;

    public RedisClientRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RateLimitProperties properties,
            RedisScript<Long> tokenBucketScript,
            PacingInfrastructureMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.properties = properties;
        this.tokenBucketScript = tokenBucketScript;
        this.metrics = metrics;
    }

    @Override
    public boolean tryAcquire(String clientId) {
        RateLimitProperties.ClientLimit clientLimit =
                properties.findClient(clientId)
                        .orElse(null);

        if (clientLimit == null) {
            metrics.recordRateLimit(false);
            return false;
        }

        Long allowed = redisTemplate.execute(
                tokenBucketScript,
                List.of(keyFactory.rateLimit(clientId)),
                Long.toString(clientLimit.capacity()),
                Double.toString(
                        clientLimit.refillTokensPerSecond()
                ),
                Long.toString(properties.idleTtl().toMillis())
        );

        boolean acquired = Long.valueOf(1L).equals(allowed);
        metrics.recordRateLimit(acquired);
        return acquired;
    }
}
