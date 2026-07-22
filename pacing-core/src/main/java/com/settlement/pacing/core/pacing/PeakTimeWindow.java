package com.settlement.pacing.core.pacing;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

public record PeakTimeWindow(
        LocalTime startTime,
        LocalTime endTime,
        ZoneId zoneId
) {
    public PeakTimeWindow {
        if (startTime == null || endTime == null || zoneId == null) {
            throw new IllegalArgumentException("피크 시작 시각, 종료 시각, 시간대는 null일 수 없습니다");
        }

        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("피크 시작 시각은 종료 시각보다 이전이어야 합니다");
        }
    }

    /**
     * 주어진 시각이 피크 시간에 포함되는지 확인한다.
     */
    public boolean contains(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("확인할 시각은 null일 수 없습니다");
        }

        LocalTime localTime = instant.atZone(zoneId).toLocalTime();

        return !localTime.isBefore(startTime) && localTime.isBefore(endTime);
    }
}
