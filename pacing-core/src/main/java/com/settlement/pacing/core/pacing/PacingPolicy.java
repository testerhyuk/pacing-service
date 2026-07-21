package com.settlement.pacing.core.pacing;

public interface PacingPolicy {
    PacingDecision decide(PacingContext context, Rate sampleRate);
}
