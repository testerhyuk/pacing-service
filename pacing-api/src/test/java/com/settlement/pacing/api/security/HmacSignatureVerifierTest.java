package com.settlement.pacing.api.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacSignatureVerifierTest {
    private static final String SECRET_KEY =
            "test-secret-key-with-at-least-32-characters";
    private static final String CANONICAL_REQUEST =
            "POST\n/path\nclient\n1784763000\nnonce\nbody-hash";

    private final HmacSignatureVerifier verifier =
            new HmacSignatureVerifier();

    @Test
    void 동일한_키로_생성한_서명은_검증에_성공한다()
            throws Exception {
        String signature = createSignature(
                CANONICAL_REQUEST,
                SECRET_KEY
        );

        assertThat(verifier.verify(
                CANONICAL_REQUEST,
                signature,
                SECRET_KEY
        )).isTrue();
    }

    @Test
    void 다른_키로_생성한_서명은_검증에_실패한다()
            throws Exception {
        String signature = createSignature(
                CANONICAL_REQUEST,
                "different-secret-key"
        );

        assertThat(verifier.verify(
                CANONICAL_REQUEST,
                signature,
                SECRET_KEY
        )).isFalse();
    }

    @Test
    void canonical_request가_바뀌면_검증에_실패한다()
            throws Exception {
        String signature = createSignature(
                CANONICAL_REQUEST,
                SECRET_KEY
        );

        assertThat(verifier.verify(
                CANONICAL_REQUEST + "-changed",
                signature,
                SECRET_KEY
        )).isFalse();
    }

    @Test
    void 서명이_16진수_형식이_아니면_false를_반환한다() {
        assertThat(verifier.verify(
                CANONICAL_REQUEST,
                "not-hex-signature",
                SECRET_KEY
        )).isFalse();
    }

    @Test
    void 필수값이_비어있으면_거절한다() {
        assertThatThrownBy(() -> verifier.verify(
                CANONICAL_REQUEST,
                " ",
                SECRET_KEY
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private String createSignature(
            String canonicalRequest,
            String secretKey
    ) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));

        return HexFormat.of().formatHex(
                mac.doFinal(
                        canonicalRequest.getBytes(
                                StandardCharsets.UTF_8
                        )
                )
        );
    }
}
