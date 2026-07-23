package com.settlement.pacing.api.security;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class CanonicalRequestBuilder {

    /**
     * HMAC 서명 계산에 사용할 요청 원문을 생성한다.
     */
    public String build(
            String httpMethod,
            String requestPath,
            String clientId,
            String timestamp,
            String nonce,
            byte[] requestBody
    ) {
        validateValue(httpMethod, "HTTP 메서드");
        validateValue(requestPath, "요청 경로");
        validateValue(clientId, "clientId");
        validateValue(timestamp, "timestamp");
        validateValue(nonce, "nonce");

        if (requestBody == null) {
            throw new IllegalArgumentException(
                    "요청 본문은 null일 수 없습니다"
            );
        }

        return String.join(
                "\n",
                httpMethod.toUpperCase(Locale.ROOT),
                requestPath,
                clientId,
                timestamp,
                nonce,
                sha256Hex(requestBody)
        );
    }

    private String sha256Hex(byte[] value) {
        try {
            MessageDigest messageDigest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    messageDigest.digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 알고리즘을 사용할 수 없습니다",
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
                    fieldName + "은 null이거나 비어있을 수 없습니다"
            );
        }

        if (value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    fieldName + "에 줄바꿈을 포함할 수 없습니다"
            );
        }
    }
}
