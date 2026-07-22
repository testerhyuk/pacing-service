package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacingRequestTest {
    private final Instant requestedAt =
            Instant.parse("2026-07-21T06:00:00Z");

    @Test
    void 페이싱_판단_요청을_생성한다() {
        PacingRequest request = new PacingRequest(
                "request-1",
                "campaign-1",
                requestedAt
        );

        assertThat(request.requestId()).isEqualTo("request-1");
        assertThat(request.campaignId()).isEqualTo("campaign-1");
        assertThat(request.requestedAt()).isEqualTo(requestedAt);
    }

    @Test
    void requestId가_null이면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new PacingRequest(null, "campaign-1", requestedAt)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void requestId가_비어있으면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new PacingRequest(" ", "campaign-1", requestedAt)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void campaignId가_null이면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new PacingRequest("request-1", null, requestedAt)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaignId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void campaignId가_비어있으면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new PacingRequest("request-1", " ", requestedAt)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaignId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void 요청_시각이_null이면_생성할_수_없다() {
        assertThatThrownBy(() ->
                new PacingRequest("request-1", "campaign-1", null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("요청 시각은 null일 수 없습니다");
    }
}
