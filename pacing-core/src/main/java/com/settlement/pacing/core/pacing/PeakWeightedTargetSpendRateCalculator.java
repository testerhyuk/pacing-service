package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.Campaign;

import java.time.*;

public class PeakWeightedTargetSpendRateCalculator implements TargetSpendRateCalculator {
    private final PeakTimeWindow peakTimeWindow;
    private final TrafficWeight trafficWeight;

    public PeakWeightedTargetSpendRateCalculator(
            PeakTimeWindow peakTimeWindow,
            TrafficWeight trafficWeight
    ) {
        if (peakTimeWindow == null || trafficWeight == null) {
            throw new IllegalArgumentException("피크 시간과 트래픽 가중치는 null일 수 없습니다");
        }

        this.peakTimeWindow = peakTimeWindow;
        this.trafficWeight = trafficWeight;
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

        double totalWeightedSeconds = weightedSeconds(campaign.startAt(), campaign.endAt());
        double elapsedWeightedSeconds = weightedSeconds(campaign.startAt(), now);

        return new Rate(elapsedWeightedSeconds / totalWeightedSeconds);
    }

    /**
     * 주어진 기간의 일반 시간과 피크 시간에 가중치를 적용한다.
     */
    private double weightedSeconds(Instant from, Instant to) {
        ZoneId zoneId = peakTimeWindow.zoneId();

        LocalDate startDate = from.atZone(zoneId).toLocalDate();
        LocalDate endDate = to.atZone(zoneId).toLocalDate();

        double result = 0.0;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(zoneId).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant();
            Instant segmentStart = laterOf(from, dayStart);
            Instant segmentEnd = earlierOf(to, dayEnd);

            if (!segmentStart.isBefore(segmentEnd)) {
                continue;
            }

            ZonedDateTime peakStartDateTime = date.atTime(peakTimeWindow.startTime()).atZone(zoneId);
            ZonedDateTime peakEndDateTime = date.atTime(peakTimeWindow.endTime()).atZone(zoneId);

            Instant peakStart = peakStartDateTime.toInstant();
            Instant peakEnd = peakEndDateTime.toInstant();

            double totalSeconds = secondsBetween(segmentStart, segmentEnd);
            double peakSeconds = overlapSeconds(
                    segmentStart,
                    segmentEnd,
                    peakStart,
                    peakEnd
            );

            double normalSeconds = totalSeconds - peakSeconds;

            result += normalSeconds * trafficWeight.normalWeight();

            result += peakSeconds * trafficWeight.peakWeight();

        }

        return result;
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
