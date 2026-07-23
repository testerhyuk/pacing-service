package com.settlement.pacing.api.decision.support;

import com.settlement.pacing.core.pacing.Rate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sha256SampleRateGeneratorTest {
    private Sha256SampleRateGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new Sha256SampleRateGenerator();
    }

    @Test
    void 결과는_0_이상_1_미만이다() {
        for (int index = 0; index < 100; index++) {
            Rate rate = generator.generate(
                    "request-" + index,
                    "campaign-" + index
            );

            assertThat(rate.value())
                    .isGreaterThanOrEqualTo(0.0)
                    .isLessThan(1.0);
        }
    }

    @Test
    void 같은_입력은_같은_Rate를_반환한다() {
        Rate first = generator.generate("request-1", "campaign-1");
        Rate second = generator.generate("request-1", "campaign-1");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void 다른_requestId는_다른_Rate를_생성할_수_있다() {
        Rate first = generator.generate("request-1", "campaign-1");
        Rate second = generator.generate("request-2", "campaign-1");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 다른_campaignId는_다른_Rate를_생성할_수_있다() {
        Rate first = generator.generate("request-1", "campaign-1");
        Rate second = generator.generate("request-1", "campaign-2");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void null_입력을_거절한다() {
        assertThatThrownBy(
                () -> generator.generate(null, "campaign-1")
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                () -> generator.generate("request-1", null)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 공백_입력을_거절한다() {
        assertThatThrownBy(
                () -> generator.generate(" ", "campaign-1")
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                () -> generator.generate("request-1", " ")
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
