package com.settlement.pacing.infrastructure.pacing;

import com.settlement.pacing.api.gateway.PacingStateSnapshot;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PacingStateSnapshotPersistenceCoordinator {

    private final PacingStateSnapshotStore snapshotStore;

    /**
     * 이 API 인스턴스가 PostgreSQL 저장을 시도한
     * 캠페인별 최신 version.
     *
     * 같은 version에 대해 여러 요청이 동시에 DB 저장을
     * 수행하는 것을 막는다.
     */
    private final ConcurrentMap<String, Long> persistedVersions =
            new ConcurrentHashMap<>();

    public PacingStateSnapshotPersistenceCoordinator(
            PacingStateSnapshotStore snapshotStore
    ) {
        this.snapshotStore = snapshotStore;
    }

    public void persistIfNeeded(
            String campaignId,
            PacingStateSnapshot snapshot
    ) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "campaignId는 null이거나 비어있을 수 없습니다"
            );
        }

        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "PacingStateSnapshot은 null일 수 없습니다"
            );
        }

        long snapshotVersion = snapshot.version();

        AtomicBoolean persistenceOwner =
                new AtomicBoolean(false);

        AtomicReference<Long> previousVersion =
                new AtomicReference<>();

        /*
         * 같은 캠페인/version에 대해서 하나의 요청만
         * PostgreSQL 저장 담당자가 된다.
         *
         * DB 저장 전에 version을 선점해서
         * 동시에 들어온 다른 요청의 중복 DB 쓰기를 막는다.
         */
        persistedVersions.compute(
                campaignId,
                (key, currentVersion) -> {
                    if (currentVersion == null
                            || currentVersion < snapshotVersion) {

                        previousVersion.set(currentVersion);
                        persistenceOwner.set(true);

                        return snapshotVersion;
                    }

                    return currentVersion;
                }
        );

        if (!persistenceOwner.get()) {
            return;
        }

        try {
            snapshotStore.saveIfNewer(
                    campaignId,
                    snapshot
            );
        } catch (RuntimeException exception) {
            /*
             * DB 저장이 실패했는데 version 선점 상태를 그대로 두면
             * 이후 같은 version이 다시 들어와도 저장을 시도하지 않게 된다.
             *
             * 따라서 실패한 선점을 이전 상태로 돌린다.
             */
            rollbackPersistenceClaim(
                    campaignId,
                    snapshotVersion,
                    previousVersion.get()
            );

            throw exception;
        }
    }

    /**
     * 캠페인의 Redis/DB 상태를 삭제할 때
     * 로컬 version 추적 상태도 같이 제거해야 한다.
     */
    public void forget(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "campaignId는 null이거나 비어있을 수 없습니다"
            );
        }

        persistedVersions.remove(campaignId);
    }

    private void rollbackPersistenceClaim(
            String campaignId,
            long failedVersion,
            Long previousVersion
    ) {
        persistedVersions.compute(
                campaignId,
                (key, currentVersion) -> {
                    if (currentVersion != null
                            && currentVersion == failedVersion) {

                        return previousVersion;
                    }

                    return currentVersion;
                }
        );
    }
}