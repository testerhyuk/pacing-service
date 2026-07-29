package com.settlement.pacing.worker;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.billing.BillingEventProcessor;
import com.settlement.pacing.core.billing.BillingEventType;
import com.settlement.pacing.core.billing.BillingResult;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.infrastructure.budget.BudgetReservationEntity;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.worker.BillingEventPersistenceService;
import com.settlement.pacing.infrastructure.worker.RedisBillingTransition;
import com.settlement.pacing.infrastructure.worker.RedisReservationSnapshot;
import com.settlement.pacing.infrastructure.worker.RedisWorkerStateStore;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingGateway;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingResult;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingStatus;
import com.settlement.pacing.worker.billing.message.BillingEventMessage;
import com.settlement.pacing.worker.expiration.application.ExpirationBatchResult;
import com.settlement.pacing.worker.expiration.application.ExpiredReservationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(
        classes = PacingWorkerApplication.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
                "spring.flyway.enabled=true",
                "pacing.infrastructure.redis.key-prefix=worker-it",
                "pacing.infrastructure.redis.campaign-cache-ttl=30s",
                "pacing.infrastructure.redis.campaign-cache-load-lock-ttl=3s",
                "pacing.infrastructure.redis.campaign-cache-load-wait-timeout=2s",
                "pacing.infrastructure.redis.campaign-cache-load-retry-interval=20ms",
                "pacing.infrastructure.redis.recovery-lock-ttl=5s",
                "pacing.infrastructure.redis.recovery-wait-timeout=2s",
                "pacing.infrastructure.redis.recovery-retry-interval=20ms",
                "pacing.worker.processed-event-ttl=1h",
                "pacing.worker.terminal-reservation-ttl=1h",
                "pacing.worker.kafka.billing-topic=billing.events.worker-it",
                "pacing.worker.kafka.consumer-group=pacing-worker-it",
                "pacing.worker.kafka.concurrency=1",
                "pacing.worker.kafka.partitions=1",
                "pacing.worker.kafka.replication-factor=1",
                "pacing.worker.kafka.retry-attempts=3",
                "pacing.worker.kafka.initial-backoff-millis=500",
                "pacing.worker.kafka.backoff-multiplier=1.0",
                "pacing.worker.kafka.max-backoff-millis=500",
                "pacing.worker.expiration.enabled=false",
                "pacing.worker.expiration.fixed-delay=1h",
                "pacing.worker.expiration.batch-size=100"
        }
)
class WorkerIntegrationTest {
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 7, 26);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16.14-alpine")
            );

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7.4.9-alpine")
            ).withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse("apache/kafka:4.3.1")
            );

    @DynamicPropertySource
    static void workerProperties(
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
        registry.add(
                "spring.kafka.bootstrap-servers",
                KAFKA::getBootstrapServers
        );
    }

    @Autowired
    private BillingEventProcessingGateway billingGateway;

    @Autowired
    private ExpiredReservationGateway expirationGateway;

    @Autowired
    private RedisBudgetStateStore budgetStateStore;

    @Autowired
    private RedisWorkerStateStore workerStateStore;

    @Autowired
    private RedisKeyFactory keyFactory;

    @Autowired
    private BillingEventPersistenceService persistenceService;

    @Autowired
    private BillingEventProcessor processor;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Test
    void 과금은_예약액을_소진액으로_원자적으로_전환하고_중복_반영하지_않는다()
            throws Exception {
        String campaignId = "campaign-charge";
        String reservationId = "reservation-charge";
        Instant reservedAt =
                Instant.parse("2026-07-26T01:00:00Z");
        seedReserved(
                campaignId,
                reservationId,
                1_000L,
                10_000L,
                reservedAt,
                reservedAt.plusSeconds(300)
        );
        BillingEvent event = event(
                "event-charge",
                reservationId,
                900L,
                reservedAt.plusSeconds(30)
        );

        BillingEventProcessingResult applied =
                billingGateway.process(event);
        BillingEventProcessingResult duplicate =
                billingGateway.process(event);

        assertThat(applied.status())
                .isEqualTo(BillingEventProcessingStatus.APPLIED);
        assertThat(duplicate.status())
                .isEqualTo(BillingEventProcessingStatus.DUPLICATE);
        assertThat(duplicate.appliedAmount())
                .isEqualTo(new Money(900L));

        BudgetState budget = budget(campaignId);
        assertThat(budget.totalSpentAmount())
                .isEqualTo(new Money(900L));
        assertThat(budget.totalReservedAmount())
                .isEqualTo(Money.zero());
        assertReservation(
                reservationId,
                "CONFIRMED",
                900L,
                1L
        );
        assertTerminalReservationHasTtl(
                campaignId,
                reservationId
        );
        assertBillingCompleted("event-charge");
    }

    @Test
    void 실제_과금액이_예약액을_넘으면_전액을_반영하고_초과액을_기록한다() {
        String campaignId = "campaign-overage";
        String reservationId = "reservation-overage";
        Instant reservedAt =
                Instant.parse("2026-07-26T02:00:00Z");
        seedReserved(
                campaignId,
                reservationId,
                100L,
                100L,
                reservedAt,
                reservedAt.plusSeconds(300)
        );

        BillingEventProcessingResult result =
                billingGateway.process(event(
                        "event-overage",
                        reservationId,
                        150L,
                        reservedAt.plusSeconds(10)
                ));

        assertThat(result.appliedAmount())
                .isEqualTo(new Money(150L));
        assertThat(result.totalOverageAmount())
                .isEqualTo(new Money(50L));
        assertThat(result.dailyOverageAmount())
                .isEqualTo(new Money(50L));
        assertThat(budget(campaignId).totalSpentAmount())
                .isEqualTo(new Money(150L));

        OverageValues persisted = jdbcClient.sql("""
                        SELECT total_overage_amount,
                               daily_overage_amount
                        FROM billing_event
                        WHERE event_id = :eventId
                        """)
                .param("eventId", "event-overage")
                .query((resultSet, rowNumber) ->
                        new OverageValues(
                                resultSet.getLong(
                                        "total_overage_amount"
                                ),
                                resultSet.getLong(
                                        "daily_overage_amount"
                                )
                        )
                )
                .single();
        assertThat(persisted)
                .isEqualTo(new OverageValues(50L, 50L));
    }

    @Test
    void Redis_반영_후_PostgreSQL_저장_전에_장애가_나도_재처리로_복구한다() {
        String campaignId = "campaign-partial";
        String reservationId = "reservation-partial";
        Instant reservedAt =
                Instant.parse("2026-07-26T03:00:00Z");
        seedReserved(
                campaignId,
                reservationId,
                1_000L,
                10_000L,
                reservedAt,
                reservedAt.plusSeconds(300)
        );
        BillingEvent event = event(
                "event-partial",
                reservationId,
                800L,
                reservedAt.plusSeconds(10)
        );

        persistenceService.register(event);
        BudgetReservationEntity entity =
                persistenceService.findReservation(
                        reservationId
                ).orElseThrow();
        RedisReservationSnapshot reservation =
                workerStateStore.getOrInitialize(entity);
        BudgetState before = budget(campaignId);
        BillingResult domainResult = processor.process(
                before,
                reservation.reservation(),
                event,
                reservation.appliedAmount()
        );
        RedisBillingTransition transition =
                workerStateStore.applyBillingEvent(
                        event,
                        before,
                        reservation,
                        domainResult
                );
        assertThat(transition.transitionStatus())
                .isEqualTo(
                        RedisBillingTransition
                                .RedisTransitionStatus.APPLIED
                );

        BillingEventProcessingResult recovered =
                billingGateway.process(event);

        assertThat(recovered.status())
                .isEqualTo(BillingEventProcessingStatus.DUPLICATE);
        assertThat(budget(campaignId).totalSpentAmount())
                .isEqualTo(new Money(800L));
        assertBillingCompleted("event-partial");
        assertReservation(
                reservationId,
                "CONFIRMED",
                800L,
                1L
        );
    }

    @Test
    void 만료된_예약은_예약액을_해제하고_EXPIRED로_동기화한다()
            throws Exception {
        String campaignId = "campaign-expiration";
        String reservationId = "reservation-expiration";
        Instant now = Instant.now();
        seedReserved(
                campaignId,
                reservationId,
                400L,
                10_000L,
                now.minusSeconds(300),
                now.minusSeconds(10)
        );

        ExpirationBatchResult result =
                expirationGateway.expire(now, 100);

        assertThat(result.expired()).isEqualTo(1);
        assertThat(budget(campaignId).totalReservedAmount())
                .isEqualTo(Money.zero());
        assertReservation(
                reservationId,
                "EXPIRED",
                0L,
                1L
        );
        assertTerminalReservationHasTtl(
                campaignId,
                reservationId
        );
    }

    @Test
    void Kafka_이벤트를_소비해_과금_완료까지_처리한다() {
        String campaignId = "campaign-kafka";
        String reservationId = "reservation-kafka";
        String eventId = "event-kafka";
        Instant reservedAt = Instant.now().minusSeconds(10);
        seedReserved(
                campaignId,
                reservationId,
                700L,
                10_000L,
                reservedAt,
                reservedAt.plusSeconds(300)
        );

        kafkaTemplate.send(
                "billing.events.worker-it",
                reservationId,
                new BillingEventMessage(
                        eventId,
                        reservationId,
                        BillingEventType.CHARGED,
                        650L,
                        Instant.now()
                )
        ).join();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    assertBillingCompleted(eventId);
                    assertReservation(
                            reservationId,
                            "CONFIRMED",
                            650L,
                            1L
                    );
                    assertThat(
                            budget(campaignId)
                                    .totalSpentAmount()
                    ).isEqualTo(new Money(650L));
                });
    }

    @Test
    void 과금_확정_후_보정과_취소를_순서대로_반영한다() {
        String campaignId = "campaign-adjust-cancel";
        String reservationId = "reservation-adjust-cancel";
        Instant reservedAt =
                Instant.parse("2026-07-26T04:00:00Z");
        seedReserved(
                campaignId,
                reservationId,
                1_000L,
                10_000L,
                reservedAt,
                reservedAt.plusSeconds(300)
        );

        billingGateway.process(event(
                "event-confirm",
                reservationId,
                900L,
                reservedAt.plusSeconds(10)
        ));
        BillingEventProcessingResult adjusted =
                billingGateway.process(new BillingEvent(
                        "event-adjust",
                        reservationId,
                        BillingEventType.ADJUSTED,
                        new Money(1_100L),
                        reservedAt.plusSeconds(20)
                ));
        BillingEventProcessingResult cancelled =
                billingGateway.process(new BillingEvent(
                        "event-cancel",
                        reservationId,
                        BillingEventType.CANCELLED,
                        new Money(1_100L),
                        reservedAt.plusSeconds(30)
                ));

        assertThat(adjusted.appliedAmount())
                .isEqualTo(new Money(1_100L));
        assertThat(cancelled.reservationStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.appliedAmount())
                .isEqualTo(Money.zero());
        assertThat(budget(campaignId).totalSpentAmount())
                .isEqualTo(Money.zero());
        assertReservation(
                reservationId,
                "CANCELLED",
                0L,
                3L
        );
    }

    @Test
    void 만료_처리_후_도착한_지연_과금도_실제_소진액으로_반영한다() {
        String campaignId = "campaign-late-charge";
        String reservationId = "reservation-late-charge";
        Instant now = Instant.now();
        Instant reservedAt = now.minusSeconds(300);
        seedReserved(
                campaignId,
                reservationId,
                500L,
                10_000L,
                reservedAt,
                now.minusSeconds(10)
        );

        ExpirationBatchResult expiration =
                expirationGateway.expire(now, 100);
        BillingEventProcessingResult charged =
                billingGateway.process(event(
                        "event-late-charge",
                        reservationId,
                        450L,
                        now.plusSeconds(1)
                ));

        assertThat(expiration.expired()).isEqualTo(1);
        assertThat(charged.reservationStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(budget(campaignId).totalSpentAmount())
                .isEqualTo(new Money(450L));
        assertThat(budget(campaignId).totalReservedAmount())
                .isEqualTo(Money.zero());
        assertReservation(
                reservationId,
                "CONFIRMED",
                450L,
                2L
        );
    }

    @Test
    void 선행_과금보다_먼저_도착한_보정은_재시도_후_처리한다() {
        String campaignId = "campaign-out-of-order";
        String reservationId = "reservation-out-of-order";
        String chargedEventId = "event-out-of-order-charge";
        String adjustedEventId = "event-out-of-order-adjust";
        Instant reservedAt = Instant.now().minusSeconds(10);
        Instant chargedOccurredAt = reservedAt.plusSeconds(1);
        Instant adjustedOccurredAt = reservedAt.plusSeconds(2);
        seedReserved(
                campaignId,
                reservationId,
                700L,
                10_000L,
                reservedAt,
                reservedAt.plusSeconds(300)
        );

        kafkaTemplate.send(
                "billing.events.worker-it",
                reservationId,
                new BillingEventMessage(
                        adjustedEventId,
                        reservationId,
                        BillingEventType.ADJUSTED,
                        800L,
                        adjustedOccurredAt
                )
        ).join();
        kafkaTemplate.send(
                "billing.events.worker-it",
                reservationId,
                new BillingEventMessage(
                        chargedEventId,
                        reservationId,
                        BillingEventType.CHARGED,
                        650L,
                        chargedOccurredAt
                )
        ).join();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    assertBillingCompleted(chargedEventId);
                    assertBillingCompleted(adjustedEventId);
                    assertReservation(
                            reservationId,
                            "CONFIRMED",
                            800L,
                            2L
                    );
                    assertThat(
                            budget(campaignId)
                                    .totalSpentAmount()
                    ).isEqualTo(new Money(800L));
                });
    }

    @Test
    void 예약을_찾을_수_없는_이벤트는_재시도_후_DLT로_보낸다() {
        String eventId = "event-missing-reservation";
        String reservationId = "reservation-missing";

        kafkaTemplate.send(
                "billing.events.worker-it",
                reservationId,
                new BillingEventMessage(
                        eventId,
                        reservationId,
                        BillingEventType.CHARGED,
                        100L,
                        Instant.now()
                )
        ).join();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    DeadLetterValues values = jdbcClient.sql("""
                                    SELECT processing_status,
                                           failure_reason
                                    FROM billing_event
                                    WHERE event_id = :eventId
                                    """)
                            .param("eventId", eventId)
                            .query((resultSet, rowNumber) ->
                                    new DeadLetterValues(
                                            resultSet.getString(
                                                    "processing_status"
                                            ),
                                            resultSet.getString(
                                                    "failure_reason"
                                            )
                                    )
                            )
                            .single();

                    assertThat(values.processingStatus())
                            .isEqualTo("DEAD_LETTER");
                    assertThat(values.failureReason())
                            .isEqualTo(
                                    "Kafka 재시도 횟수를 초과했습니다"
                            );
                });
    }

    private void seedReserved(
            String campaignId,
            String reservationId,
            long reservationAmount,
            long budgetLimit,
            Instant reservedAt,
            Instant expiresAt
    ) {
        insertCampaign(campaignId, budgetLimit);
        jdbcClient.sql("""
                        INSERT INTO budget_reservation (
                            reservation_id,
                            campaign_id,
                            budget_date,
                            amount,
                            applied_amount,
                            status,
                            reserved_at,
                            expires_at,
                            version,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :reservationId,
                            :campaignId,
                            :budgetDate,
                            :amount,
                            0,
                            'RESERVED',
                            :reservedAt,
                            :expiresAt,
                            0,
                            :now,
                            :now
                        )
                        """)
                .param("reservationId", reservationId)
                .param("campaignId", campaignId)
                .param("budgetDate", BUDGET_DATE)
                .param("amount", reservationAmount)
                .param(
                        "reservedAt",
                        reservedAt.atOffset(ZoneOffset.UTC)
                )
                .param(
                        "expiresAt",
                        expiresAt.atOffset(ZoneOffset.UTC)
                )
                .param("now", Instant.now().atOffset(ZoneOffset.UTC))
                .update();

        budgetStateStore.initializeIfAbsent(new BudgetState(
                campaignId,
                BUDGET_DATE,
                new Money(budgetLimit),
                Money.zero(),
                new Money(reservationAmount),
                new Money(budgetLimit),
                Money.zero(),
                new Money(reservationAmount)
        ));
    }

    private void insertCampaign(
            String campaignId,
            long budgetLimit
    ) {
        Instant now = Instant.now();
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
                            :budget,
                            :budget
                        )
                        """)
                .param("campaignId", campaignId)
                .param(
                        "startAt",
                        now.minus(Duration.ofDays(1))
                                .atOffset(ZoneOffset.UTC)
                )
                .param(
                        "endAt",
                        now.plus(Duration.ofDays(1))
                                .atOffset(ZoneOffset.UTC)
                )
                .param("budget", budgetLimit)
                .update();
    }

    private BillingEvent event(
            String eventId,
            String reservationId,
            long amount,
            Instant occurredAt
    ) {
        return new BillingEvent(
                eventId,
                reservationId,
                BillingEventType.CHARGED,
                new Money(amount),
                occurredAt
        );
    }

    private BudgetState budget(String campaignId) {
        return budgetStateStore.read(campaignId, BUDGET_DATE)
                .budgetState();
    }

    private void assertTerminalReservationHasTtl(
            String campaignId,
            String reservationId
    ) throws Exception {
        String key = keyFactory.reservation(
                campaignId,
                reservationId
        );
        long ttlMillis = Long.parseLong(
                REDIS.execInContainer(
                        "redis-cli",
                        "PTTL",
                        key
                ).getStdout().trim()
        );

        assertThat(ttlMillis)
                .isBetween(1L, Duration.ofHours(1).toMillis());
    }

    private void assertReservation(
            String reservationId,
            String expectedStatus,
            long expectedAppliedAmount,
            long expectedVersion
    ) {
        ReservationValues reservation = jdbcClient.sql("""
                        SELECT status, applied_amount, version
                        FROM budget_reservation
                        WHERE reservation_id = :reservationId
                        """)
                .param("reservationId", reservationId)
                .query((resultSet, rowNumber) ->
                        new ReservationValues(
                                resultSet.getString("status"),
                                resultSet.getLong(
                                        "applied_amount"
                                ),
                                resultSet.getLong("version")
                        )
                )
                .single();

        assertThat(reservation).isEqualTo(
                new ReservationValues(
                        expectedStatus,
                        expectedAppliedAmount,
                        expectedVersion
                )
        );
    }

    private void assertBillingCompleted(String eventId) {
        java.util.Optional<String> status = jdbcClient.sql("""
                        SELECT processing_status
                        FROM billing_event
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(String.class)
                .optional();

        assertThat(status).contains("COMPLETED");
    }

    private record ReservationValues(
            String status,
            long appliedAmount,
            long version
    ) {
    }

    private record OverageValues(
            long total,
            long daily
    ) {
    }

    private record DeadLetterValues(
            String processingStatus,
            String failureReason
    ) {
    }
}
