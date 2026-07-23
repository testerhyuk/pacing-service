package com.settlement.pacing.api.error;

public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException() {
        super("동일한 예약 ID로 다른 예약 정보가 요청되었습니다");
    }
}
