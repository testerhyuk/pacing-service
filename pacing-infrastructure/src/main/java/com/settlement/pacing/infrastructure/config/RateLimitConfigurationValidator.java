package com.settlement.pacing.infrastructure.config;

import com.settlement.pacing.api.config.HmacSecurityProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Set;
import java.util.TreeSet;

public class RateLimitConfigurationValidator implements SmartInitializingSingleton {

    private final HmacSecurityProperties hmacProperties;
    private final RateLimitProperties rateLimitProperties;

    public RateLimitConfigurationValidator(
            HmacSecurityProperties hmacProperties,
            RateLimitProperties rateLimitProperties
    ) {
        if (hmacProperties == null
                || rateLimitProperties == null) {
            throw new IllegalArgumentException(
                    "HMAC 설정과 Rate Limit 설정은 null일 수 없습니다"
            );
        }

        this.hmacProperties = hmacProperties;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> hmacClientIds =
                hmacProperties.clients().keySet();

        Set<String> rateLimitClientIds =
                rateLimitProperties.clients().keySet();

        Set<String> missingClientIds =
                new TreeSet<>(hmacClientIds);
        missingClientIds.removeAll(rateLimitClientIds);

        Set<String> unknownClientIds =
                new TreeSet<>(rateLimitClientIds);
        unknownClientIds.removeAll(hmacClientIds);

        if (!missingClientIds.isEmpty()
                || !unknownClientIds.isEmpty()) {
            throw new IllegalStateException(
                    "HMAC Client와 Rate Limit Client 설정이 일치하지 않습니다"
                            + ", Rate Limit 누락=" + missingClientIds
                            + ", 등록되지 않은 Client=" + unknownClientIds
            );
        }
    }
}