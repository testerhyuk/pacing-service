package com.settlement.pacing.infrastructure.common;

import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClusterSlotContractTest {
    private static final String CAMPAIGN_ID = "campaign-slot-contract";
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 8, 13);

    private final RedisKeyFactory keyFactory =
            new RedisKeyFactory(properties());

    @Test
    void 예산_예약에_사용하는_모든_Key는_같은_slot을_사용한다() {
        assertSameSlot(List.of(
                keyFactory.totalBudget(CAMPAIGN_ID),
                keyFactory.dailyBudget(CAMPAIGN_ID, BUDGET_DATE),
                keyFactory.reservation(CAMPAIGN_ID, "reservation-1"),
                keyFactory.reservationExpiry(CAMPAIGN_ID),
                keyFactory.campaignReservationPersistencePending(
                        CAMPAIGN_ID
                )
        ));
    }

    @Test
    void 예약_복구_pending과_processing은_같은_slot을_사용한다() {
        assertSameSlot(List.of(
                keyFactory.campaignReservationPersistencePending(
                        CAMPAIGN_ID
                ),
                keyFactory.campaignReservationPersistenceProcessing(
                        CAMPAIGN_ID
                )
        ));
    }

    @Test
    void 과금과_만료에_사용하는_모든_Key는_같은_slot을_사용한다() {
        assertSameSlot(List.of(
                keyFactory.totalBudget(CAMPAIGN_ID),
                keyFactory.dailyBudget(CAMPAIGN_ID, BUDGET_DATE),
                keyFactory.reservation(CAMPAIGN_ID, "reservation-1"),
                keyFactory.reservationExpiry(CAMPAIGN_ID),
                keyFactory.billingEvent(CAMPAIGN_ID, "event-1")
        ));
    }

    @Test
    void 페이싱_판단과_관측_Key는_캠페인별로_같은_slot을_사용한다() {
        assertSameSlot(List.of(
                keyFactory.campaign(CAMPAIGN_ID),
                keyFactory.totalBudget(CAMPAIGN_ID),
                keyFactory.dailyBudget(CAMPAIGN_ID, BUDGET_DATE),
                keyFactory.pacingState(CAMPAIGN_ID),
                keyFactory.pacingObservation(CAMPAIGN_ID, 1_000L),
                keyFactory.pacingObservation(CAMPAIGN_ID, 2_000L),
                keyFactory.pacingObservationDecisionIds(
                        CAMPAIGN_ID,
                        1_000L
                ),
                keyFactory.pacingObservationReservationIds(
                        CAMPAIGN_ID,
                        1_000L
                )
        ));
    }

    @Test
    void 요청_인증_Key는_clientId별로_같은_slot을_사용한다() {
        assertSameSlot(List.of(
                keyFactory.nonce("ad-server", "nonce-1"),
                keyFactory.rateLimit("ad-server")
        ));
    }

    @Test
    void 서로_다른_캠페인은_서로_다른_slot으로_분산된다() {
        int first = SlotHash.getSlot(
                keyFactory.totalBudget("campaign-slot-1")
        );
        int second = SlotHash.getSlot(
                keyFactory.totalBudget("campaign-slot-2")
        );

        assertThat(first).isNotEqualTo(second);
    }

    private void assertSameSlot(List<String> keys) {
        assertThat(keys)
                .extracting(SlotHash::getSlot)
                .containsOnly(SlotHash.getSlot(keys.getFirst()));
    }

    private RedisInfrastructureProperties properties() {
        return new RedisInfrastructureProperties(
                "cluster-contract",
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofMillis(50),
                Duration.ofSeconds(3),
                Duration.ofSeconds(2),
                Duration.ofMillis(20)
        );
    }
}
