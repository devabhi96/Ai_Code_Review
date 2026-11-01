package com.devabhi.aicodereview.llm;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Small retry helper: exponential backoff with jitter; a server-sent retry-after wins (capped). */
public final class RetryPolicy {

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final DoubleSupplier jitter;
    private final Sleeper sleeper;

    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs,
                       DoubleSupplier jitter, Sleeper sleeper) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitter = jitter;
        this.sleeper = sleeper;
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 1_000, 30_000,
                () -> ThreadLocalRandom.current().nextDouble(), Thread::sleep);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Wait time after the given failed attempt (1-based). jitter 0..1 maps to 50%..100% of the delay. */
    public long delayMs(int failedAttempt, Long retryAfterMs) {
        if (retryAfterMs != null) {
            return Math.min(Math.max(retryAfterMs, 0), maxDelayMs);
        }
        long exponential = baseDelayMs;
        for (int i = 1; i < failedAttempt && exponential < maxDelayMs; i++) {
            exponential *= 2;
        }
        exponential = Math.min(exponential, maxDelayMs);
        return (long) (exponential * (0.5 + 0.5 * jitter.getAsDouble()));
    }

    public void sleep(long millis) throws InterruptedException {
        sleeper.sleep(millis);
    }
}