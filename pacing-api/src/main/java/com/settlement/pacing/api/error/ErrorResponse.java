package com.settlement.pacing.api.error;

import java.time.Instant;

public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String path
) {
    public static ErrorResponse of(
            ErrorCode errorCode,
            String message,
            String path
    ) {
        return new ErrorResponse(
                errorCode.name(),
                message,
                Instant.now(),
                path
        );
    }
}
