package com.settlement.pacing.api.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalRequestBuilderTest {
    private final CanonicalRequestBuilder builder =
            new CanonicalRequestBuilder();

    @Test
    void 요청값을_정해진_순서로_결합한다() {
        String canonicalRequest = builder.build(
                "post",
                "/internal/v1/budget-reservations",
                "auction-server",
                "1784763000",
                "nonce-1",
                "{}".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(canonicalRequest).isEqualTo("""
                POST
                /internal/v1/budget-reservations
                auction-server
                1784763000
                nonce-1
                44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a""");
    }

    @Test
    void HTTP_메서드는_대문자로_정규화한다() {
        String canonicalRequest = builder.build(
                "post",
                "/path",
                "client",
                "1784763000",
                "nonce",
                new byte[0]
        );

        assertThat(canonicalRequest).startsWith("POST\n");
    }

    @Test
    void 요청_본문이_달라지면_canonical_request도_달라진다() {
        String first = builder.build(
                "POST",
                "/path",
                "client",
                "1784763000",
                "nonce",
                "{\"amount\":100}".getBytes(StandardCharsets.UTF_8)
        );
        String second = builder.build(
                "POST",
                "/path",
                "client",
                "1784763000",
                "nonce",
                "{\"amount\":200}".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 필수_문자열이_비어있으면_거절한다() {
        assertThatThrownBy(() -> builder.build(
                " ",
                "/path",
                "client",
                "1784763000",
                "nonce",
                new byte[0]
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 요청_본문이_null이면_거절한다() {
        assertThatThrownBy(() -> builder.build(
                "POST",
                "/path",
                "client",
                "1784763000",
                "nonce",
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 줄바꿈이_포함된_헤더값을_거절한다() {
        assertThatThrownBy(() -> builder.build(
                "POST",
                "/path",
                "client\nforged",
                "1784763000",
                "nonce",
                new byte[0]
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
