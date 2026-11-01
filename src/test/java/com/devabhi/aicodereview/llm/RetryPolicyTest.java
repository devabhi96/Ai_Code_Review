package com.devabhi.aicodereview.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static RetryPolicy policy(double jitter) {
        return new RetryPolicy(5, 1_000, 30_000, () -> jitter, ms -> { });
    }

    @Test
    void delaysDoubleWithEachFailedAttempt() {
        RetryPolicy p = policy(1.0);
        assertThat(p.delayMs(1, null)).isEqualTo(1_000);
        assertThat(p.delayMs(2, null)).isEqualTo(2_000);
        assertThat(p.delayMs(3, null)).isEqualTo(4_000);
    }

    @Test
    void jitterScalesTheDelayBetweenHalfAndFull() {
        assertThat(policy(0.0).delayMs(2, null)).isEqualTo(1_000);
        assertThat(policy(1.0).delayMs(2, null)).isEqualTo(2_000);
    }

    @Test
    void serverRetryAfterWinsButIsCapped() {
        RetryPolicy p = policy(0.0);
        assertThat(p.delayMs(1, 5_000L)).isEqualTo(5_000);
        assertThat(p.delayMs(1, 120_000L)).isEqualTo(30_000);
    }
}