package com.settlement.pacing.api.error;

public class InvalidRequestException extends IllegalArgumentException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
