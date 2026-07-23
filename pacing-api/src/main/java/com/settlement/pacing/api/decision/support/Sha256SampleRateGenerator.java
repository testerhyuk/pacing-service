package com.settlement.pacing.api.decision.support;

import com.settlement.pacing.api.error.InvalidRequestException;
import com.settlement.pacing.core.pacing.Rate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class Sha256SampleRateGenerator implements SampleRateGenerator {
    @Override
    public Rate generate(String requestId, String campaignId) {
        validateIdentifier(requestId, "requestId");
        validateIdentifier(campaignId, "campaignId");

        String samplingKey  = createSamplingKey(requestId, campaignId);

        byte[] hash = createSha256Hash(samplingKey);

        long value = ByteBuffer.wrap(hash).getLong() >>> 11;

        return new Rate(value / (double) (1L << 53));
    }

    private void validateIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(
                    fieldName + "는 null이거나 비어있을 수 없습니다"
            );
        }
    }

    private String createSamplingKey(String requestId, String campaignId) {
        return requestId.length()
                + ":"
                + requestId
                + "|"
                + campaignId.length()
                + ":"
                + campaignId;
    }

    private byte[] createSha256Hash(String samplingKey) {
        try {
            MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");

            return sha256Digest.digest(samplingKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다" ,e);
        }
    }
}
