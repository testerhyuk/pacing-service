package com.settlement.pacing.api.admin.application;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.gateway.PeakPolicyGateway;
import com.settlement.pacing.core.pacing.PeakPolicy;
import com.settlement.pacing.core.pacing.PeakPolicyProvider;
import com.settlement.pacing.core.pacing.PeakTimeWindow;
import com.settlement.pacing.core.pacing.TrafficWeight;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DynamicPeakPolicyService
        implements PeakPolicyProvider {
    private final PeakPolicyGateway peakPolicyGateway;
    private final PacingProperties pacingProperties;
    private final AuditLogger auditLogger;
    private final Clock clock;
    private final AtomicReference<PeakPolicy> current =
            new AtomicReference<>();

    public DynamicPeakPolicyService(
            PeakPolicyGateway peakPolicyGateway,
            PacingProperties pacingProperties,
            AuditLogger auditLogger,
            Clock clock
    ) {
        this.peakPolicyGateway = peakPolicyGateway;
        this.pacingProperties = pacingProperties;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @PostConstruct
    public void initialize() {
        PeakPolicy configured = configuredPolicy();
        PeakPolicy initial = peakPolicyGateway.find()
                .orElseGet(() -> {
                    peakPolicyGateway.save(configured);
                    return configured;
                });
        current.set(initial);
    }

    @Scheduled(
            fixedDelayString =
                    "${pacing.peak.refresh-interval:5s}"
    )
    public void refresh() {
        peakPolicyGateway.find().ifPresent(current::set);
    }

    public PeakPolicy update(
            UpdatePeakPolicyCommand command,
            String clientId,
            String requestId
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "피크 정책 변경 명령은 null일 수 없습니다"
            );
        }

        PeakPolicy before = current();
        PeakPolicy updated = new PeakPolicy(
                new PeakTimeWindow(
                        command.startTime(),
                        command.endTime(),
                        command.zoneId()
                ),
                new TrafficWeight(
                        command.normalWeight(),
                        command.peakWeight()
                )
        );

        peakPolicyGateway.save(updated);
        current.set(updated);

        auditLogger.log(new AuditLogger.AuditEvent(
                AuditLogger.EventType.PEAK_POLICY_CHANGE,
                clientId,
                requestId,
                "peak-policy",
                before.toString(),
                updated.toString(),
                AuditLogger.Result.SUCCESS,
                null,
                clock.instant()
        ));

        return updated;
    }

    @Override
    public PeakPolicy current() {
        PeakPolicy peakPolicy = current.get();
        if (peakPolicy == null) {
            throw new IllegalStateException(
                    "피크 정책이 초기화되지 않았습니다"
            );
        }
        return peakPolicy;
    }

    private PeakPolicy configuredPolicy() {
        PacingProperties.Peak peak = pacingProperties.peak();
        return new PeakPolicy(
                new PeakTimeWindow(
                        peak.startTime(),
                        peak.endTime(),
                        peak.zoneId()
                ),
                new TrafficWeight(
                        peak.normalWeight(),
                        peak.peakWeight()
                )
        );
    }
}
