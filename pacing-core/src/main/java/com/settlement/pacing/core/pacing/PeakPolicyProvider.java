package com.settlement.pacing.core.pacing;

@FunctionalInterface
public interface PeakPolicyProvider {
    PeakPolicy current();
}
