package com.settlement.pacing.infrastructure.campaign;

import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public class RedisCampaignCache {
    private static final String CAMPAIGN_ID = "campaignId";
    private static final String STATUS = "status";
    private static final String START_AT = "startAtEpochMillis";
    private static final String END_AT = "endAtEpochMillis";
    private static final String PACING_STRATEGY = "pacingStrategy";

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisInfrastructureProperties properties;

    public RedisCampaignCache(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisInfrastructureProperties properties
    ) {
        this.redisTemplate = requireNonNull(
                redisTemplate,
                "StringRedisTemplate"
        );
        this.keyFactory = requireNonNull(
                keyFactory,
                "RedisKeyFactory"
        );
        this.properties = requireNonNull(
                properties,
                "Redis 설정"
        );
    }

    public Optional<Campaign> find(String campaignId) {
        String key = keyFactory.campaign(campaignId);
        Map<Object, Object> values =
                redisTemplate.opsForHash().entries(key);

        if (values.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(new Campaign(
                    required(values, CAMPAIGN_ID),
                    CampaignStatus.valueOf(
                            required(values, STATUS)
                    ),
                    Instant.ofEpochMilli(
                            Long.parseLong(required(values, START_AT))
                    ),
                    Instant.ofEpochMilli(
                            Long.parseLong(required(values, END_AT))
                    ),
                    PacingStrategy.valueOf(
                            required(values, PACING_STRATEGY)
                    )
            ));
        } catch (DataAccessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            redisTemplate.delete(key);
            return Optional.empty();
        }
    }

    public void put(Campaign campaign) {
        if (campaign == null) {
            throw new IllegalArgumentException(
                    "Campaign은 null일 수 없습니다"
            );
        }

        String key = keyFactory.campaign(campaign.campaignId());

        redisTemplate.opsForHash().putAll(
                key,
                Map.of(
                        CAMPAIGN_ID, campaign.campaignId(),
                        STATUS, campaign.status().name(),
                        START_AT, Long.toString(
                                campaign.startAt().toEpochMilli()
                        ),
                        END_AT, Long.toString(
                                campaign.endAt().toEpochMilli()
                        ),
                        PACING_STRATEGY,
                        campaign.pacingStrategy().name()
                )
        );
        redisTemplate.expire(
                key,
                properties.campaignCacheTtl()
        );
    }

    public void evict(String campaignId) {
        redisTemplate.delete(keyFactory.campaign(campaignId));
    }

    private String required(
            Map<Object, Object> values,
            String field
    ) {
        Object value = values.get(field);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(
                    "캠페인 Cache 필드가 없습니다: " + field
            );
        }

        return value.toString();
    }

    private static <T> T requireNonNull(
            T value,
            String fieldName
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + "은 null일 수 없습니다"
            );
        }

        return value;
    }
}
