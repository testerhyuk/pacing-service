package com.settlement.pacing.worker.billing.message;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.billing.BillingEventType;
import com.settlement.pacing.core.budget.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingEventMessageTest {

    @Test
    void Kafka_메시지를_도메인_이벤트로_변환한다() {
        Instant occurredAt =
                Instant.parse("2026-07-26T01:02:03.123456789Z");
        BillingEventMessage message = new BillingEventMessage(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                900L,
                occurredAt
        );

        BillingEvent event = message.toDomain();

        assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.reservationId())
                .isEqualTo("reservation-1");
        assertThat(event.eventType())
                .isEqualTo(BillingEventType.CHARGED);
        assertThat(event.actualAmount())
                .isEqualTo(new Money(900L));
        assertThat(event.occurredAt())
                .isEqualTo(
                        Instant.parse(
                                "2026-07-26T01:02:03.123456Z"
                        )
                );
    }

    @Test
    void 필수값이_없거나_금액이_0이면_변환할_수_없다() {
        assertThatThrownBy(() -> new BillingEventMessage(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                0L,
                Instant.parse("2026-07-26T01:02:03Z")
        ).toDomain())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new BillingEventMessage(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                100L,
                null
        ).toDomain())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
