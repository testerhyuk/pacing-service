package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.Campaign;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.HashSet;
import java.util.Set;

public class PeakWeightedTargetSpendRateCalculator implements TargetSpendRateCalculator {
    private final PeakPolicyProvider peakPolicyProvider;

    public PeakWeightedTargetSpendRateCalculator(
            PeakTimeWindow peakTimeWindow,
            TrafficWeight trafficWeight
    ) {
        this(staticProvider(peakTimeWindow, trafficWeight));
    }

    public PeakWeightedTargetSpendRateCalculator(
            PeakPolicyProvider peakPolicyProvider
    ) {
        if (peakPolicyProvider == null) {
            throw new IllegalArgumentException(
                    "피크 정책 제공자는 null일 수 없습니다"
            );
        }

        this.peakPolicyProvider = peakPolicyProvider;
    }

    private static PeakPolicyProvider staticProvider(
            PeakTimeWindow peakTimeWindow,
            TrafficWeight trafficWeight
    ) {
        if (peakTimeWindow == null || trafficWeight == null) {
            throw new IllegalArgumentException(
                    "피크 시간과 트래픽 가중치는 null일 수 없습니다"
            );
        }

        PeakPolicy peakPolicy = new PeakPolicy(
                peakTimeWindow,
                trafficWeight
        );
        return () -> peakPolicy;
    }

    @Override
    public Rate calculate(Campaign campaign, Instant now) {
        if (campaign == null || now == null) {
            throw new IllegalArgumentException("캠페인과 현재 시각은 null일 수 없습니다");
        }

        // 캠페인 시작 전에는 소진 목표가 없다
        if (!now.isAfter(campaign.startAt())) return Rate.zero();

        // 캠페인 종료 시점부터는 전체 예산 소진이 목표다
        if (!now.isBefore(campaign.endAt())) return Rate.full();

        PeakPolicy peakPolicy = peakPolicyProvider.current();
        if (peakPolicy == null) {
            throw new IllegalStateException(
                    "현재 피크 정책을 조회할 수 없습니다"
            );
        }

        double totalWeightedSeconds = weightedSeconds(
                campaign.startAt(),
                campaign.endAt(),
                peakPolicy
        );
        double elapsedWeightedSeconds = weightedSeconds(
                campaign.startAt(),
                now,
                peakPolicy
        );

        return new Rate(elapsedWeightedSeconds / totalWeightedSeconds);
    }

    /**
     * 주어진 기간의 일반 시간과 피크 시간에 가중치를 적용한다.
     */
    private double weightedSeconds(
            Instant from,
            Instant to,
            PeakPolicy peakPolicy
    ) {
        double totalSeconds = secondsBetween(from, to);
        double peakSeconds = peakSeconds(
                from,
                to,
                peakPolicy.timeWindow()
        );
        TrafficWeight trafficWeight = peakPolicy.trafficWeight();

        return totalSeconds * trafficWeight.normalWeight()
                + peakSeconds
                * (trafficWeight.peakWeight()
                - trafficWeight.normalWeight());
    }

    /**
     * 시작일과 종료일만 직접 계산하고, 중간의 완전한 날짜는
     * 날짜 수로 합산한다. DST 전환일만 별도로 보정한다.
     */
    private double peakSeconds(
            Instant from,
            Instant to,
            PeakTimeWindow peakTimeWindow
    ) {
        ZoneId zoneId = peakTimeWindow.zoneId();
        LocalDate startDate = from.atZone(zoneId).toLocalDate();
        LocalDate endDate = to.atZone(zoneId).toLocalDate();

        if (startDate.equals(endDate)) {
            return peakOverlapOnDate(
                    startDate,
                    from,
                    to,
                    peakTimeWindow
            );
        }

        Instant startDayEnd = startDate.plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant();
        Instant endDayStart = endDate
                .atStartOfDay(zoneId)
                .toInstant();

        double result = peakOverlapOnDate(
                startDate,
                from,
                startDayEnd,
                peakTimeWindow
        );
        result += peakOverlapOnDate(
                endDate,
                endDayStart,
                to,
                peakTimeWindow
        );

        LocalDate firstFullDate = startDate.plusDays(1);
        long fullDays = ChronoUnit.DAYS.between(
                firstFullDate,
                endDate
        );

        if (fullDays <= 0) {
            return result;
        }

        double nominalPeakSeconds = Duration.between(
                peakTimeWindow.startTime(),
                peakTimeWindow.endTime()
        ).toSeconds();
        result += fullDays * nominalPeakSeconds;

        for (LocalDate transitionDate : transitionDates(
                firstFullDate,
                endDate,
                zoneId
        )) {
            result -= nominalPeakSeconds;
            result += peakDurationOnDate(
                    transitionDate,
                    zoneId,
                    peakTimeWindow
            );
        }

        return result;
    }

    private Set<LocalDate> transitionDates(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            ZoneId zoneId
    ) {
        Set<LocalDate> dates = new HashSet<>();
        ZoneRules rules = zoneId.getRules();
        Instant end = toExclusive.atStartOfDay(zoneId).toInstant();
        Instant cursor = fromInclusive.atStartOfDay(zoneId)
                .toInstant()
                .minusNanos(1);

        while (true) {
            ZoneOffsetTransition transition =
                    rules.nextTransition(cursor);

            if (transition == null
                    || !transition.getInstant().isBefore(end)) {
                break;
            }

            LocalDate date = transition.getInstant()
                    .atZone(zoneId)
                    .toLocalDate();

            if (!date.isBefore(fromInclusive)
                    && date.isBefore(toExclusive)) {
                dates.add(date);
            }

            cursor = transition.getInstant().plusNanos(1);
        }

        return dates;
    }

    private double peakDurationOnDate(
            LocalDate date,
            ZoneId zoneId,
            PeakTimeWindow peakTimeWindow
    ) {
        Instant peakStart = date
                .atTime(peakTimeWindow.startTime())
                .atZone(zoneId)
                .toInstant();
        Instant peakEnd = date
                .atTime(peakTimeWindow.endTime())
                .atZone(zoneId)
                .toInstant();

        return secondsBetween(peakStart, peakEnd);
    }

    private double peakOverlapOnDate(
            LocalDate date,
            Instant from,
            Instant to,
            PeakTimeWindow peakTimeWindow
    ) {
        ZoneId zoneId = peakTimeWindow.zoneId();
        Instant peakStart = date
                .atTime(peakTimeWindow.startTime())
                .atZone(zoneId)
                .toInstant();
        Instant peakEnd = date
                .atTime(peakTimeWindow.endTime())
                .atZone(zoneId)
                .toInstant();

        return overlapSeconds(
                from,
                to,
                peakStart,
                peakEnd
        );
    }

    /**
     * 두 기간이 겹치는 시간을 초로 반환한다.
     */
    private double overlapSeconds(
            Instant firstStart,
            Instant firstEnd,
            Instant secondStart,
            Instant secondEnd
    ) {
        Instant overlapStart = laterOf(firstStart, secondStart);
        Instant overlapEnd = earlierOf(firstEnd, secondEnd);

        if (!overlapStart.isBefore(overlapEnd)) {
            return 0.0;
        }

        return secondsBetween(overlapStart, overlapEnd);
    }

    private Instant laterOf(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private Instant earlierOf(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private double secondsBetween(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);

        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }
}
