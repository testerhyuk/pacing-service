package com.settlement.pacing.api.monitoring;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class StorageFailureClassifierTest {
    private final StorageFailureClassifier classifier =
            new StorageFailureClassifier();

    @Test
    void 원인_체인에_Redis_예외가_있으면_Redis로_분류한다() {
        RuntimeException exception = new RuntimeException(
                new RedisConnectionTestException()
        );

        assertThat(classifier.classify(exception))
                .isEqualTo(StorageType.REDIS);
    }

    @Test
    void 원인_체인에_SQL_예외가_있으면_PostgreSQL로_분류한다() {
        RuntimeException exception = new RuntimeException(
                new SQLException("connection unavailable")
        );

        assertThat(classifier.classify(exception))
                .isEqualTo(StorageType.POSTGRESQL);
    }

    @Test
    void Redis_데이터_손상_메시지는_Redis로_분류한다() {
        assertThat(classifier.classify(
                new RuntimeException(
                        "Redis 예산 상태 값이 올바르지 않습니다"
                )
        )).isEqualTo(StorageType.REDIS);
    }

    @Test
    void 저장소를_판별할_수_없으면_UNKNOWN으로_분류한다() {
        assertThat(classifier.classify(
                new IllegalStateException("unknown")
        )).isEqualTo(StorageType.UNKNOWN);
    }

    private static final class RedisConnectionTestException
            extends RuntimeException {
    }
}
