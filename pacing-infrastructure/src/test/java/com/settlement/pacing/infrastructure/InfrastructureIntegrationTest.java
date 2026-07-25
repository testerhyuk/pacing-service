package com.settlement.pacing.infrastructure;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.PacingApiApplication;
import com.settlement.pacing.api.gateway.BudgetReservationGateway;
import com.settlement.pacing.api.gateway.BudgetStateQueryGateway;
import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.api.gateway.PacingStateGateway;
import com.settlement.pacing.api.gateway.PacingStateSnapshot;
import com.settlement.pacing.api.gateway.ReservationExecutionResult;
import com.settlement.pacing.api.gateway.ReservationExecutionStatus;
import com.settlement.pacing.api.security.ClientRateLimiter;
import com.settlement.pacing.api.security.NonceStore;
import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.pacing.PacingState;
import com.settlement.pacing.core.pacing.Rate;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Autowired
    private CampaignQueryGateway campaignQueryGateway;

    @Autowired
    private BudgetStateQueryGateway budgetStateQueryGateway;

    @Autowired
    private BudgetReservationGateway budgetReservationGateway;

    @Autowired
    private PacingStateGateway pacingStateGateway;

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

    @Test
    void 자동_설정으로_모든_API_포트가_조립된다() {
        assertThat(campaignQueryGateway).isNotNull();
        assertThat(budgetStateQueryGateway).isNotNull();
        assertThat(budgetReservationGateway).isNotNull();
        assertThat(pacingStateGateway).isNotNull();
        assertThat(nonceStore).isNotNull();
        assertThat(clientRateLimiter).isNotNull();
        assertThat(auditLogger).isNotNull();
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
        return new BudgetReservation(
                reservationId,
                campaignId,
                BUDGET_DATE,
                new Money(amount),
                NOW,
                NOW.plus(Duration.ofMinutes(5))
        );
    }

    private record AuditValues(
            String beforeValue,
            String afterValue
    ) {
    }

}
