package com.settlement.pacing.api.admin.web;

import com.settlement.pacing.api.admin.application.UpdatePeakPolicyCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.time.ZoneId;

public record PeakPolicyRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull ZoneId zoneId,
        @DecimalMin(value = "0.0", inclusive = false)
        double normalWeight,
        @DecimalMin(value = "0.0", inclusive = false)
        double peakWeight
) {
    public UpdatePeakPolicyCommand toCommand() {
        return new UpdatePeakPolicyCommand(
                startTime,
                endTime,
                zoneId,
                normalWeight,
                peakWeight
        );
    }
}
