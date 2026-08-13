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
                1L,
                occurredAt
        );

        assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.reservationId()).isEqualTo("reservation-1");
        assertThat(event.eventType()).isEqualTo(BillingEventType.CHARGED);
        assertThat(event.targetAppliedAmount())
                .isEqualTo(new Money(900));
        assertThat(event.sequence()).isEqualTo(1L);
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
                        1L,
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
                        1L,
                        occurredAt
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void CHARGED의_최종_적용_금액이_0원이면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new BillingEvent(
                        "event-1",
                        "reservation-1",
                        BillingEventType.CHARGED,
                        Money.zero(),
                        1L,
                        occurredAt
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("과금 확정 후 적용 금액은 0보다 커야 합니다");
    }

    @Test
    void CANCELLED는_최종_적용_금액_0원을_허용한다() {
        BillingEvent event = new BillingEvent(
                "event-1",
                "reservation-1",
                BillingEventType.CANCELLED,
                Money.zero(),
                2L,
                occurredAt
        );

        assertThat(event.targetAppliedAmount()).isEqualTo(Money.zero());
    }

    @Test
    void 순번이_0이면_생성할_수_없다() {
        assertThatThrownBy(() -> new BillingEvent(
                "event-1",
                "reservation-1",
                BillingEventType.ADJUSTED,
                new Money(100),
                0L,
                occurredAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("과금 이벤트 순번은 0보다 커야 합니다");
    }
}
