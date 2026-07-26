package com.settlement.pacing.infrastructure;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.PacingApiApplication;
import com.settlement.pacing.api.gateway.BudgetReservationGateway;
import com.settlement.pacing.api.gateway.BudgetStateQueryGateway;
import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.api.gateway.CampaignManagementGateway;
import com.settlement.pacing.api.gateway.PeakPolicyGateway;
import com.settlement.pacing.api.gateway.PacingStateGateway;
import com.settlement.pacing.api.gateway.PacingStateSnapshot;
import com.settlement.pacing.api.gateway.PacingObservationGateway;
import com.settlement.pacing.api.gateway.ReservationExecutionResult;
import com.settlement.pacing.api.gateway.ReservationExecutionStatus;
import com.settlement.pacing.api.security.ClientRateLimiter;
import com.settlement.pacing.api.security.NonceStore;
import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.pacing.PacingState;
import com.settlement.pacing.core.pacing.PacingObservation;
import com.settlement.pacing.core.pacing.DecisionType;
import com.settlement.pacing.core.pacing.PeakPolicy;
import com.settlement.pacing.core.pacing.PeakTimeWindow;
import com.settlement.pacing.core.pacing.Rate;
import com.settlement.pacing.core.pacing.TrafficWeight;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = PacingApiApplication.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
                "spring.flyway.enabled=true",
                "pacing.infrastructure.redis.key-prefix=test-pacing",
                "pacing.infrastructure.redis.campaign-cache-ttl=30s",
                "pacing.infrastructure.redis.recovery-lock-ttl=5s",
                "pacing.infrastructure.redis.recovery-wait-timeout=2s",
                "pacing.infrastructure.redis.recovery-retry-interval=20ms",
                "pacing.infrastructure.rate-limit.idle-ttl=10m",
                "pacing.infrastructure.rate-limit.clients.test-client.capacity=2",
                "pacing.infrastructure.rate-limit.clients.test-client.refill-tokens-per-second=0.000001",
                "pacing.security.hmac.timestamp-tolerance=60s",
                "pacing.security.hmac.nonce-ttl=2m",
                "pacing.security.hmac.max-request-body-bytes=65536",
                "pacing.security.hmac.clients.test-client.current-secret-key=0123456789abcdef0123456789abcdef",
                "pacing.security.hmac.clients.test-client.permissions[0]=PACING_DECIDE"
        }
)
class InfrastructureIntegrationTest {
    private static final Instant NOW =
            Instant.parse("2026-07-25T00:00:00Z");
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 7, 25);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16-alpine")
            );

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7.4-alpine")
            ).withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                POSTGRES::getJdbcUrl
        );
        registry.add(
                "spring.datasource.username",
                POSTGRES::getUsername
        );
        registry.add(
                "spring.datasource.password",
                POSTGRES::getPassword
        );
        registry.add(
                "spring.data.redis.host",
                REDIS::getHost
        );
        registry.add(
                "spring.data.redis.port",
                () -> REDIS.getMappedPort(6379)
        );
        registry.add(
                "spring.data.redis.password",
                () -> ""
        );
    }

    @Autowired
    private CampaignQueryGateway campaignQueryGateway;

    @Autowired
    private CampaignManagementGateway campaignManagementGateway;

    @Autowired
    private PeakPolicyGateway peakPolicyGateway;

    @Autowired
    private BudgetStateQueryGateway budgetStateQueryGateway;

    @Autowired
    private BudgetReservationGateway budgetReservationGateway;

    @Autowired
    private PacingStateGateway pacingStateGateway;

    @Autowired
    private PacingObservationGateway pacingObservationGateway;

    @Autowired
    private NonceStore nonceStore;

    @Autowired
    private ClientRateLimiter clientRateLimiter;

    @Autowired
    private AuditLogger auditLogger;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisKeyFactory keyFactory;

    @Autowired
    private Clock clock;

    @Test
    void 자동_설정으로_모든_API_포트가_조립된다() {
        assertThat(campaignQueryGateway).isNotNull();
        assertThat(budgetStateQueryGateway).isNotNull();
        assertThat(budgetReservationGateway).isNotNull();
        assertThat(pacingStateGateway).isNotNull();
        assertThat(pacingObservationGateway).isNotNull();
        assertThat(nonceStore).isNotNull();
        assertThat(clientRateLimiter).isNotNull();
        assertThat(auditLogger).isNotNull();
    }

    @Test
    void 캠페인별_판단과_예약을_구간별로_집계하고_중복은_제외한다() {
        String campaignId = "campaign-observation";
        long intervalMillis = Duration.ofSeconds(10).toMillis();
        long currentBucket = Math.floorDiv(
                clock.instant().toEpochMilli(),
                intervalMillis
        ) * intervalMillis;
        Instant eventAt = Instant.ofEpochMilli(
                currentBucket - 1L
        );
        Instant observedAt = Instant.ofEpochMilli(
                currentBucket + 1L
        );

        for (int index = 0; index < 100; index++) {
            pacingObservationGateway.recordDecision(
                    "request-" + index,
                    campaignId,
                    index < 40
                            ? DecisionType.PASS
                            : DecisionType.BLOCK,
                    eventAt
            );
        }

        assertThat(pacingObservationGateway.recordDecision(
                "request-0",
                campaignId,
                DecisionType.PASS,
                observedAt
        )).isFalse();

        for (int index = 0; index < 8; index++) {
            pacingObservationGateway.recordReservation(
                    "reservation-" + index,
                    campaignId,
                    new Money(1_000L),
                    eventAt
            );
        }

        assertThat(pacingObservationGateway.recordReservation(
                "reservation-0",
                campaignId,
                new Money(1_000L),
                eventAt
        )).isFalse();

        PacingObservation observation =
                pacingObservationGateway.recent(
                        campaignId,
                        observedAt
                );

        assertThat(observation.intervalCount()).isEqualTo(1);
        assertThat(observation.decisionCount()).isEqualTo(100L);
        assertThat(observation.passCount()).isEqualTo(40L);
        assertThat(observation.reservationCount()).isEqualTo(8L);
        assertThat(observation.reservedAmount())
                .isEqualTo(new Money(8_000L));
        assertThat(
                observation.estimatedFullPassAmountPerInterval()
        ).isEqualTo(20_000.0);
    }

    @Test
    void 캠페인을_PostgreSQL에서_조회하고_Redis에_Cache한다() {
        String campaignId = "campaign-query";
        insertCampaign(campaignId, 1_000L, 500L);

        Campaign campaign = campaignQueryGateway
                .findById(campaignId)
                .orElseThrow();

        assertThat(campaign.campaignId()).isEqualTo(campaignId);
        assertThat(redisTemplate.opsForHash().entries(
                keyFactory.campaign(campaignId)
        )).isNotEmpty();
    }

    @Test
    void Redis_예산이_없으면_PostgreSQL에서_복구한다() {
        String campaignId = "campaign-recovery";
        insertCampaign(campaignId, 1_000L, 500L);

        BudgetState state = budgetStateQueryGateway.find(
                campaignId,
                BUDGET_DATE
        ).orElseThrow();

        assertThat(state.totalBudget()).isEqualTo(new Money(1_000L));
        assertThat(state.dailyBudgetLimit()).isEqualTo(new Money(500L));
        assertThat(state.totalSpentAmount()).isEqualTo(Money.zero());
        assertThat(state.totalReservedAmount()).isEqualTo(Money.zero());
        assertThat(redisTemplate.hasKey(
                keyFactory.totalBudget(campaignId)
        )).isTrue();
        assertThat(redisTemplate.hasKey(
                keyFactory.dailyBudget(campaignId, BUDGET_DATE)
        )).isTrue();
    }

    @Test
    void 예약은_전체와_일일_예산을_원자적으로_차감하고_멱등하다() {
        String campaignId = "campaign-reservation";
        insertCampaign(campaignId, 1_000L, 500L);

        BudgetReservation reservation = reservation(
                "reservation-1",
                campaignId,
                300L
        );

        ReservationExecutionResult created =
                budgetReservationGateway.reserve(reservation);
        ReservationExecutionResult existing =
                budgetReservationGateway.reserve(reservation);
        ReservationExecutionResult insufficient =
                budgetReservationGateway.reserve(reservation(
                        "reservation-2",
                        campaignId,
                        201L
                ));

        assertThat(created.status())
                .isEqualTo(ReservationExecutionStatus.CREATED);
        assertThat(existing.status())
                .isEqualTo(
                        ReservationExecutionStatus.ALREADY_EXISTS
                );
        assertThat(insufficient.status())
                .isEqualTo(
                        ReservationExecutionStatus.INSUFFICIENT_BUDGET
                );

        BudgetState state = budgetStateQueryGateway.find(
                campaignId,
                BUDGET_DATE
        ).orElseThrow();
        assertThat(state.totalReservedAmount())
                .isEqualTo(new Money(300L));
        assertThat(state.dailyReservedAmount())
                .isEqualTo(new Money(300L));

        long persistedCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM budget_reservation
                        WHERE reservation_id = :reservationId
                        """)
                .param("reservationId", "reservation-1")
                .query(Long.class)
                .single();
        assertThat(persistedCount).isEqualTo(1L);
    }

    @Test
    void 동시_예약도_일일_예산을_초과하지_않는다()
            throws Exception {
        String campaignId = "campaign-concurrent";
        insertCampaign(campaignId, 2_000L, 500L);
        budgetStateQueryGateway.find(campaignId, BUDGET_DATE)
                .orElseThrow();

        int requestCount = 10;
        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationExecutionStatus>> futures =
                new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                String reservationId =
                        "concurrent-reservation-" + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return budgetReservationGateway.reserve(
                            reservation(
                                    reservationId,
                                    campaignId,
                                    100L
                            )
                    ).status();
                }));
            }

            start.countDown();

            List<ReservationExecutionStatus> statuses =
                    new ArrayList<>();
            for (Future<ReservationExecutionStatus> future
                    : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).filteredOn(
                    status -> status
                            == ReservationExecutionStatus.CREATED
            ).hasSize(5);
            assertThat(statuses).filteredOn(
                    status -> status
                            == ReservationExecutionStatus
                            .INSUFFICIENT_BUDGET
            ).hasSize(5);
        } finally {
            executor.shutdownNow();
        }

        BudgetState state = budgetStateQueryGateway.find(
                campaignId,
                BUDGET_DATE
        ).orElseThrow();
        assertThat(state.dailyReservedAmount())
                .isEqualTo(new Money(500L));
    }

    @Test
    void 서로_다른_캠페인이_같은_예약_ID를_사용하면_후속_예약을_보정한다() {
        String firstCampaignId = "campaign-global-id-first";
        String secondCampaignId = "campaign-global-id-second";
        String reservationId = "global-reservation-id";
        insertCampaign(firstCampaignId, 1_000L, 500L);
        insertCampaign(secondCampaignId, 1_000L, 500L);

        ReservationExecutionResult first =
                budgetReservationGateway.reserve(reservation(
                        reservationId,
                        firstCampaignId,
                        100L
                ));
        ReservationExecutionResult conflict =
                budgetReservationGateway.reserve(reservation(
                        reservationId,
                        secondCampaignId,
                        100L
                ));

        assertThat(first.status())
                .isEqualTo(ReservationExecutionStatus.CREATED);
        assertThat(conflict.status())
                .isEqualTo(ReservationExecutionStatus.CONFLICT);

        BudgetState secondState = budgetStateQueryGateway.find(
                secondCampaignId,
                BUDGET_DATE
        ).orElseThrow();
        assertThat(secondState.totalReservedAmount())
                .isEqualTo(Money.zero());
        assertThat(secondState.dailyReservedAmount())
                .isEqualTo(Money.zero());
        assertThat(redisTemplate.hasKey(
                keyFactory.reservation(
                        secondCampaignId,
                        reservationId
                )
        )).isFalse();
    }

    @Test
    void Redis_예산_상태가_유실되면_예약_이력까지_포함해_복구한다() {
        String campaignId = "campaign-recovery-with-reservation";
        insertCampaign(campaignId, 1_000L, 500L);

        BudgetReservation reservation = reservation(
                "reservation-for-recovery",
                campaignId,
                300L
        );
        assertThat(budgetReservationGateway.reserve(reservation)
                .status()).isEqualTo(
                ReservationExecutionStatus.CREATED
        );

        redisTemplate.delete(List.of(
                keyFactory.totalBudget(campaignId),
                keyFactory.dailyBudget(campaignId, BUDGET_DATE),
                keyFactory.reservation(
                        campaignId,
                        reservation.reservationId()
                ),
                keyFactory.reservationExpiry(campaignId)
        ));

        BudgetState recovered = budgetStateQueryGateway.find(
                campaignId,
                BUDGET_DATE
        ).orElseThrow();

        assertThat(recovered.totalReservedAmount())
                .isEqualTo(new Money(300L));
        assertThat(recovered.dailyReservedAmount())
                .isEqualTo(new Money(300L));
    }

    @Test
    void Redis_페이싱_상태가_유실되면_PostgreSQL_스냅샷으로_복구한다() {
        String campaignId = "campaign-pacing-recovery";
        insertCampaign(campaignId, 1_000L, 500L);

        PacingStateSnapshot initial =
                pacingStateGateway.getOrInitialize(
                        campaignId,
                        new PacingState(
                                new Rate(0.1),
                                NOW.minusSeconds(10)
                        )
                );
        PacingState versionOne = new PacingState(
                new Rate(0.2),
                NOW
        );
        assertThat(pacingStateGateway.compareAndSet(
                campaignId,
                initial.version(),
                versionOne
        )).isTrue();

        redisTemplate.delete(keyFactory.pacingState(campaignId));

        PacingStateSnapshot recovered = pacingStateGateway
                .findByCampaignId(campaignId)
                .orElseThrow();
        PacingState versionTwo = new PacingState(
                new Rate(0.3),
                NOW.plusSeconds(10)
        );

        assertThat(recovered.version()).isEqualTo(1L);
        assertThat(recovered.pacingState()).isEqualTo(versionOne);
        assertThat(pacingStateGateway.compareAndSet(
                campaignId,
                recovered.version(),
                versionTwo
        )).isTrue();
    }

    @Test
    void Lua_예약은_double_정밀도를_넘는_금액도_정확하게_검사한다() {
        String campaignId = "campaign-large-budget";
        insertCampaign(
                campaignId,
                Long.MAX_VALUE,
                Long.MAX_VALUE
        );

        ReservationExecutionResult almostAll =
                budgetReservationGateway.reserve(reservation(
                        "large-reservation-1",
                        campaignId,
                        Long.MAX_VALUE - 1
                ));
        ReservationExecutionResult overflow =
                budgetReservationGateway.reserve(reservation(
                        "large-reservation-2",
                        campaignId,
                        2L
                ));

        assertThat(almostAll.status())
                .isEqualTo(ReservationExecutionStatus.CREATED);
        assertThat(overflow.status())
                .isEqualTo(
                        ReservationExecutionStatus.INSUFFICIENT_BUDGET
                );
    }

    @Test
    void 페이싱_상태는_version으로_CAS하고_스냅샷을_저장한다() {
        String campaignId = "campaign-pacing";
        insertCampaign(campaignId, 1_000L, 500L);

        PacingState initialState = new PacingState(
                new Rate(0.1),
                NOW.minusSeconds(10)
        );
        PacingStateSnapshot initial =
                pacingStateGateway.getOrInitialize(
                        campaignId,
                        initialState
                );
        PacingState updatedState = new PacingState(
                new Rate(0.2),
                NOW
        );

        boolean updated = pacingStateGateway.compareAndSet(
                campaignId,
                initial.version(),
                updatedState
        );
        boolean staleUpdate = pacingStateGateway.compareAndSet(
                campaignId,
                initial.version(),
                new PacingState(new Rate(0.3), NOW.plusSeconds(1))
        );

        PacingStateSnapshot stored = pacingStateGateway
                .findByCampaignId(campaignId)
                .orElseThrow();

        assertThat(updated).isTrue();
        assertThat(staleUpdate).isFalse();
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.pacingState()).isEqualTo(updatedState);

        long persistedVersion = jdbcClient.sql("""
                        SELECT version
                        FROM pacing_state_snapshot
                        WHERE campaign_id = :campaignId
                        """)
                .param("campaignId", campaignId)
                .query(Long.class)
                .single();
        assertThat(persistedVersion).isEqualTo(1L);
    }

    @Test
    void nonce는_TTL_동안_한_번만_저장된다() {
        assertThat(nonceStore.saveIfAbsent(
                "test-client",
                "nonce-1",
                Duration.ofMinutes(2)
        )).isTrue();
        assertThat(nonceStore.saveIfAbsent(
                "test-client",
                "nonce-1",
                Duration.ofMinutes(2)
        )).isFalse();
    }

    @Test
    void Rate_Limit은_설정된_capacity까지만_허용한다() {
        assertThat(clientRateLimiter.tryAcquire("test-client"))
                .isTrue();
        assertThat(clientRateLimiter.tryAcquire("test-client"))
                .isTrue();
        assertThat(clientRateLimiter.tryAcquire("test-client"))
                .isFalse();
    }

    @Test
    void 감사_로그는_HMAC_비밀키를_저장하지_않는다() {
        auditLogger.log(new AuditLogger.AuditEvent(
                AuditLogger.EventType.HMAC_KEY_CHANGE,
                "test-client",
                "request-audit",
                "test-client",
                "old-secret-value",
                "new-secret-value",
                AuditLogger.Result.SUCCESS,
                "ROTATED",
                NOW
        ));

        AuditValues values = jdbcClient.sql("""
                        SELECT before_value, after_value
                        FROM audit_log
                        WHERE request_id = :requestId
                        """)
                .param("requestId", "request-audit")
                .query((resultSet, rowNumber) ->
                        new AuditValues(
                                resultSet.getString("before_value"),
                                resultSet.getString("after_value")
                        )
                )
                .single();

        assertThat(values.beforeValue()).isEqualTo("[REDACTED]");
        assertThat(values.afterValue()).isEqualTo("[REDACTED]");
    }

    @Test
    void 감사_로그는_저장_후_수정하거나_삭제할_수_없다() {
        auditLogger.log(new AuditLogger.AuditEvent(
                AuditLogger.EventType.CAMPAIGN_CHANGE,
                "operation-server",
                "request-immutable",
                "campaign-immutable",
                null,
                "created",
                AuditLogger.Result.SUCCESS,
                null,
                NOW
        ));

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE audit_log
                        SET reason = 'changed'
                        WHERE request_id = :requestId
                        """)
                .param("requestId", "request-immutable")
                .update())
                .isInstanceOf(
                        org.springframework.dao.DataAccessException.class
                );
    }

    @Test
    void 피크_정책은_PostgreSQL에_저장하고_다시_조회한다() {
        PeakPolicy policy = new PeakPolicy(
                new PeakTimeWindow(
                        LocalTime.of(17, 0),
                        LocalTime.of(22, 0),
                        ZoneId.of("Asia/Seoul")
                ),
                new TrafficWeight(0.4, 1.8)
        );

        peakPolicyGateway.save(policy);

        assertThat(peakPolicyGateway.find())
                .contains(policy);
    }

    @Test
    void 현재_소진예약액보다_작은_예산으로는_변경할_수_없다() {
        String campaignId = "campaign-budget-admin";
        insertCampaign(campaignId, 1_000L, 500L);
        budgetStateQueryGateway.find(campaignId, BUDGET_DATE)
                .orElseThrow();
        budgetReservationGateway.reserve(reservation(
                "reservation-budget-admin",
                campaignId,
                300L
        ));
        CampaignManagementGateway.CampaignSettings current =
                campaignManagementGateway.findById(campaignId)
                        .orElseThrow();

        CampaignManagementGateway.CampaignSettings requested =
                new CampaignManagementGateway.CampaignSettings(
                        current.campaignId(),
                        current.status(),
                        current.startAt(),
                        current.endAt(),
                        current.pacingStrategy(),
                        1_000L,
                        200L,
                        current.createdAt(),
                        NOW
                );

        assertThatThrownBy(() ->
                campaignManagementGateway.save(
                        requested,
                        BUDGET_DATE
                )
        ).isInstanceOf(
                com.settlement.pacing.api.error
                        .BudgetLimitConflictException.class
        );
    }

    private void insertCampaign(
            String campaignId,
            long totalBudget,
            long dailyBudgetLimit
    ) {
        jdbcClient.sql("""
                        INSERT INTO campaign (
                            campaign_id,
                            status,
                            start_at,
                            end_at,
                            pacing_strategy,
                            total_budget,
                            daily_budget_limit
                        )
                        VALUES (
                            :campaignId,
                            'ACTIVE',
                            :startAt,
                            :endAt,
                            'EVEN',
                            :totalBudget,
                            :dailyBudgetLimit
                        )
                        ON CONFLICT (campaign_id) DO NOTHING
                        """)
                .param("campaignId", campaignId)
                .param(
                        "startAt",
                        NOW.minus(Duration.ofDays(1))
                                .atOffset(ZoneOffset.UTC)
                )
                .param(
                        "endAt",
                        NOW.plus(Duration.ofDays(1))
                                .atOffset(ZoneOffset.UTC)
                )
                .param("totalBudget", totalBudget)
                .param("dailyBudgetLimit", dailyBudgetLimit)
                .update();
    }

    private BudgetReservation reservation(
            String reservationId,
            String campaignId,
            long amount
    ) {
        Instant reservedAt = clock.instant();

        return new BudgetReservation(
                reservationId,
                campaignId,
                BUDGET_DATE,
                new Money(amount),
                reservedAt,
                reservedAt.plus(Duration.ofMinutes(5))
        );
    }

    private record AuditValues(
            String beforeValue,
            String afterValue
    ) {
    }

}
