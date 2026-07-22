package com.settlement.pacing.core.billing;

import com.settlement.pacing.core.budget.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingEventTest {
    private final Instant occurredAt =
            Instant.parse("2026-07-21T06:01:00Z");

    @Test
    void 과금_이벤트를_생성한다() {
        BillingEvent event = new BillingEvent(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                new Money(900),
                occurredAt
        );

        assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.reservationId()).isEqualTo("reservation-1");
        assertThat(event.eventType()).isEqualTo(BillingEventType.CHARGED);
        assertThat(event.actualAmount()).isEqualTo(new Money(900));
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void eventId가_비어있으면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new BillingEvent(
                        " ",
                        "reservation-1",
                        BillingEventType.CHARGED,
                        new Money(900),
                        occurredAt
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eventId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void reservationId가_비어있으면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new BillingEvent(
                        "event-1",
                        " ",
                        BillingEventType.CHARGED,
                        new Money(900),
                        occurredAt
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void 과금_이벤트_금액이_0원이면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new BillingEvent(
                        "event-1",
                        "reservation-1",
                        BillingEventType.CHARGED,
                        Money.zero(),
                        occurredAt
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("과금 이벤트 금액은 0보다 커야 합니다");
    }
}
