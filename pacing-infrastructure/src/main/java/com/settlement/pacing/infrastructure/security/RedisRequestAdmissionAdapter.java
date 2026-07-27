package com.settlement.pacing.infrastructure.security;

import com.settlement.pacing.api.security.RequestAdmissionGateway;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.config.RateLimitProperties;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

public class RedisRequestAdmissionAdapter
        implements RequestAdmissionGateway {

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RateLimitProperties properties;
    private final RedisScript<String> admitRequestScript;
    private final PacingInfrastructureMetrics metrics;

    public RedisRequestAdmissionAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RateLimitProperties properties,
            RedisScript<String> admitRequestScript,
            PacingInfrastructureMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.properties = properties;
        this.admitRequestScript = admitRequestScript;
        this.metrics = metrics;
    }

    @Override
    public Result admit(
            String clientId,
            String nonce,
            Duration nonceTtl
    ) {
        if (nonceTtl == null
                || nonceTtl.isZero()
                || nonceTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "nonce TTL은 0보다 커야 합니다"
            );
        }

        RateLimitProperties.ClientLimit clientLimit =
                properties.findClient(clientId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Rate Limit 설정이 없는 clientId입니다: "
                                                + clientId
                                )
                        );

        String rawResult = redisTemplate.execute(
                admitRequestScript,
                List.of(
                        keyFactory.nonce(clientId, nonce),
                        keyFactory.rateLimit(clientId)
                ),
                Long.toString(nonceTtl.toMillis()),
                Long.toString(clientLimit.capacity()),
                Double.toString(
                        clientLimit.refillTokensPerSecond()
                ),
                Long.toString(
                        properties.idleTtl().toMillis()
                )
        );

        if (rawResult == null) {
            throw new IllegalStateException(
                    "Request Admission Lua 결과가 null입니다"
            );
        }

        Result result;
        try {
            result = Result.valueOf(rawResult);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "알 수 없는 Request Admission 결과입니다: "
                            + rawResult,
                    exception
            );
        }

        recordMetrics(result);

        return result;
    }

    private void recordMetrics(Result result) {
        switch (result) {
            case ALLOWED -> {
                metrics.recordNonce(true);
                metrics.recordRateLimit(true);
            }

            case NONCE_REUSED ->
                    metrics.recordNonce(false);

            case RATE_LIMITED -> {
                metrics.recordNonce(true);
                metrics.recordRateLimit(false);
            }
        }
    }
}