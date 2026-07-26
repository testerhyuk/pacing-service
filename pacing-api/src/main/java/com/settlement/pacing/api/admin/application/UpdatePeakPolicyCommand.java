package com.settlement.pacing.api.admin.application;

import java.time.LocalTime;
import java.time.ZoneId;

public record UpdatePeakPolicyCommand(
        LocalTime startTime,
        LocalTime endTime,
        ZoneId zoneId,
        double normalWeight,
        double peakWeight
) {
}
