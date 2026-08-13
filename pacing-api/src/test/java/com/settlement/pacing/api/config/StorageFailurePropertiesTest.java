package com.settlement.pacing.api.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageFailurePropertiesTest {

    @Test
    void 로그_주기는_0보다_커야_한다() {
        assertThatThrownBy(
                () -> new StorageFailureProperties(Duration.ZERO)
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                () -> new StorageFailureProperties(
                        Duration.ofSeconds(-1)
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 양수_로그_주기는_허용한다() {
        assertThatCode(
                () -> new StorageFailureProperties(
                        Duration.ofSeconds(30)
                )
        ).doesNotThrowAnyException();
    }
}
