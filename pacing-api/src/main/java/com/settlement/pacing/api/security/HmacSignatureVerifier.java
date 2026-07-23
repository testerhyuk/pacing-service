package com.settlement.pacing.api.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class HmacSignatureVerifier {
    private static final String ALGORITHM = "HmacSHA256";

    /**
     * 전달받은 서명이 서버에서 계산한 서명과 같은지 확인한다.
     */
    public boolean verify(
            String canonicalRequest,
            String providedSignature,
            String secretKey
    ) {
        validateValue(canonicalRequest, "canonical request");
        validateValue(providedSignature, "HMAC 서명");
        validateValue(secretKey, "secretKey");

        byte[] expectedSignature =
                createSignature(canonicalRequest, secretKey);

        byte[] actualSignature;

        try {
            actualSignature = HexFormat.of()
                    .parseHex(providedSignature);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        return MessageDigest.isEqual(
                expectedSignature,
                actualSignature
        );
    }

    private byte[] createSignature(
            String canonicalRequest,
            String secretKey
    ) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    ALGORITHM
            );

            mac.init(keySpec);

            return mac.doFinal(
                    canonicalRequest.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "HmacSHA256 알고리즘을 사용할 수 없습니다",
                    exception
            );
        } catch (InvalidKeyException exception) {
            throw new IllegalArgumentException(
                    "유효하지 않은 HMAC secretKey입니다",
                    exception
            );
        }
    }

    private void validateValue(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + "는 null이거나 비어있을 수 없습니다"
            );
        }
    }
}
