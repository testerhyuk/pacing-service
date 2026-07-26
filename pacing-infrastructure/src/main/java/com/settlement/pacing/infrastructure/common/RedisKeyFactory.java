package com.settlement.pacing.infrastructure.common;

import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

public class RedisKeyFactory {
    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String keyPrefix;

    public RedisKeyFactory(RedisInfrastructureProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException(
                    "Redis 설정은 null일 수 없습니다"
            );
        }

        this.keyPrefix = properties.keyPrefix();
    }

    /**
     * 캠페인 정책 Cache Key를 생성한다.
     */
    public String campaign(String campaignId) {
        return keyPrefix
                + ":campaign:{"
                + encode(campaignId)
                + "}";
    }

    /**
     * 캠페인 전체 예산 상태 Key를 생성한다.
     */
    public String totalBudget(String campaignId) {
        return keyPrefix
                + ":budget:total:{"
                + encode(campaignId)
                + "}";
    }

    /**
     * 캠페인 일일 예산 상태 Key를 생성한다.
     */
    public String dailyBudget(
            String campaignId,
            LocalDate budgetDate
    ) {
        if (budgetDate == null) {
            throw new IllegalArgumentException(
                    "예산 기준일은 null일 수 없습니다"
            );
        }

        return keyPrefix
                + ":budget:daily:{"
                + encode(campaignId)
                + "}:"
                + budgetDate;
    }

    /**
     * 캠페인의 현재 페이싱 상태 Key를 생성한다.
     */
    public String pacingState(String campaignId) {
        return keyPrefix
                + ":pacing-state:{"
                + encode(campaignId)
                + "}";
    }

    /**
     * 예산 예약 정보 Key를 생성한다.
     */
    public String reservation(
            String campaignId,
            String reservationId
    ) {
        return keyPrefix
                + ":reservation:{"
                + encode(campaignId)
                + "}:"
                + encode(reservationId);
    }

    /**
     * 캠페인별 예약 만료 인덱스 Key를 생성한다.
     */
    public String reservationExpiry(String campaignId) {
        return keyPrefix
                + ":reservation-expiry:{"
                + encode(campaignId)
                + "}";
    }

    /**
     * 예산 상태 복구 Lock Key를 생성한다.
     */
    public String recoveryLock(String campaignId) {
        return keyPrefix
                + ":recovery-lock:{"
                + encode(campaignId)
                + "}";
    }

    /**
     * HMAC nonce 중복 확인 Key를 생성한다.
     */
    public String nonce(
            String clientId,
            String nonce
    ) {
        return keyPrefix
                + ":nonce:"
                + encode(clientId)
                + ":"
                + encode(nonce);
    }

    /**
     * clientId별 Rate Limit 상태 Key를 생성한다.
     */
    public String rateLimit(String clientId) {
        return keyPrefix
                + ":rate-limit:"
                + encode(clientId);
    }

    private String encode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Redis Key 식별자는 null이거나 비어있을 수 없습니다"
            );
        }

        return KEY_ENCODER.encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
        );
    }
}