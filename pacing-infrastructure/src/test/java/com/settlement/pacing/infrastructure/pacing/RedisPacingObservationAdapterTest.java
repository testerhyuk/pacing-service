package com.settlement.pacing.infrastructure.pacing;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.core.pacing.PacingObservation;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class RedisPacingObservationAdapterTest {

    private static final String CAMPAIGN_ID = "campaign-1";

    private static final Instant NOW = Instant.parse("2026-08-15T10:00:35Z");

    private static final Clock CLOCK =
            Clock.fixed(
                    NOW,
                    ZoneId.of("UTC")
            );

    @Test
    void Redis에서_최신부터_오래된순으로_받은_bucket을_오래된순부터_최신순으로_보존한다() {
        StringRedisTemplate redisTemplate =
                mock(StringRedisTemplate.class);

        RedisScript<Long> recordDecisionScript =
                mock(RedisScript.class);

        RedisScript<Long> recordReservationScript =
                mock(RedisScript.class);

        RedisScript<List> readObservationScript =
                mock(RedisScript.class);

        RedisPacingObservationAdapter adapter =
                new RedisPacingObservationAdapter(
                        redisTemplate,
                        keyFactory(),
                        recordDecisionScript,
                        recordReservationScript,
                        readObservationScript,
                        properties(),
                        CLOCK
                );

        /*
         * Redis는 recent()의 keys 순서대로:
         *
         * 최신 → 오래된
         *
         * 500, 100, 100, 100, 100, 100
         */
        List<String> redisValues = List.of(
                "500", "500", "50", "5000",
                "100", "100", "10", "1000",
                "100", "100", "10", "1000",
                "100", "100", "10", "1000",
                "100", "100", "10", "1000",
                "100", "100", "10", "1000"
        );

        doReturn(redisValues)
                .when(redisTemplate)
                .execute(
                        eq(readObservationScript),
                        anyList()
                );

        PacingObservation observation =
                adapter.recent(
                        CAMPAIGN_ID,
                        NOW
                );

        assertThat(observation.intervals())
                .extracting(
                        PacingObservation.Interval::decisionCount
                )
                .containsExactly(
                        100L,
                        100L,
                        100L,
                        100L,
                        100L,
                        500L
                );
    }

    @Test
    void Redis에서_0인_bucket도_그대로_보존한다() {
        StringRedisTemplate redisTemplate =
                mock(StringRedisTemplate.class);

        RedisScript<Long> recordDecisionScript =
                mock(RedisScript.class);

        RedisScript<Long> recordReservationScript =
                mock(RedisScript.class);

        RedisScript<List> readObservationScript =
                mock(RedisScript.class);

        RedisPacingObservationAdapter adapter =
                new RedisPacingObservationAdapter(
                        redisTemplate,
                        keyFactory(),
                        recordDecisionScript,
                        recordReservationScript,
                        readObservationScript,
                        properties(),
                        CLOCK
                );

        /*
         * Redis 반환 순서:
         * 최신 → 오래된
         *
         * 최신 = 500
         * 그 전 = 0
         * 그 전 = 100
         * ...
         */
        List<String> redisValues = List.of(
                "500", "500", "50", "5000",
                "0", "0", "0", "0",
                "100", "100", "10", "1000",
                "100", "100", "10", "1000",
                "100", "100", "10", "1000",
                "100", "100", "10", "1000"
        );

        doReturn(redisValues)
                .when(redisTemplate)
                .execute(
                        eq(readObservationScript),
                        anyList()
                );

        PacingObservation observation =
                adapter.recent(
                        CAMPAIGN_ID,
                        NOW
                );

        assertThat(observation.intervals())
                .extracting(
                        PacingObservation.Interval::decisionCount
                )
                .containsExactly(
                        100L,
                        100L,
                        100L,
                        100L,
                        0L,
                        500L
                );
    }

    @Test
    void 모든_bucket이_0이면_empty를_반환한다() {
        StringRedisTemplate redisTemplate =
                mock(StringRedisTemplate.class);

        RedisScript<Long> recordDecisionScript =
                mock(RedisScript.class);

        RedisScript<Long> recordReservationScript =
                mock(RedisScript.class);

        RedisScript<List> readObservationScript =
                mock(RedisScript.class);

        RedisPacingObservationAdapter adapter =
                new RedisPacingObservationAdapter(
                        redisTemplate,
                        keyFactory(),
                        recordDecisionScript,
                        recordReservationScript,
                        readObservationScript,
                        properties(),
                        CLOCK
                );

        List<String> redisValues = List.of(
                "0", "0", "0", "0",
                "0", "0", "0", "0",
                "0", "0", "0", "0",
                "0", "0", "0", "0",
                "0", "0", "0", "0",
                "0", "0", "0", "0"
        );

        doReturn(redisValues)
                .when(redisTemplate)
                .execute(
                        eq(readObservationScript),
                        anyList()
                );

        PacingObservation observation =
                adapter.recent(
                        CAMPAIGN_ID,
                        NOW
                );

        assertThat(observation.intervals())
                .isEmpty();
    }

    private RedisKeyFactory keyFactory() {
        return new RedisKeyFactory(
                new RedisInfrastructureProperties(
                        "test-pacing",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(50),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(20)
                )
        );
    }

    private PacingProperties properties() {
        return new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                new PacingProperties.Observation(
                        Duration.ofMinutes(1),
                        20L,
                        0.5,
                        0.2,
                        0.1
                ),
                Duration.ofMinutes(5),
                3,
                new PacingProperties.InitialRate(
                        0.1,
                        0.1,
                        1.0
                ),
                new PacingProperties.Peak(
                        LocalTime.of(18, 0),
                        LocalTime.of(23, 0),
                        ZoneId.of("Asia/Seoul"),
                        0.5,
                        1.5
                ),
                0.5
        );
    }
}