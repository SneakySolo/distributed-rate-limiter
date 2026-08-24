package com.distributed.ratelimiter.domain;

/**
 * Immutable configuration for a rate limit policy.
 *
 * DESIGN DECISION: Why a separate config record?
 * - Makes policy explicit and testable
 * - Allows easy reconfiguration per service
 * - Clearly documents limits and refill rates
 * - In future, could load from database or config server
 *
 * CURRENT SPEC:
 * - 100 requests per minute per user+service
 * - Token Bucket: 100 tokens, refilled at 100/minute (≈1.67/sec)
 * - Leaky Bucket: 100 queue capacity, leak rate 100/minute (≈1 req/600ms)
 */
public record RateLimit(
        /**
         * Maximum capacity (tokens in Token Bucket, queue depth in Leaky Bucket).
         * Currently fixed at 100.
         */
        long capacity,

        /**
         * Refill rate: how many units per minute?
         * Currently 100 (equal to capacity for 1-minute window).
         */
        long refillRatePerMinute,

        /**
         * TTL for inactive rate limit state (in milliseconds).
         * After this duration with no requests, state expires and is reclaimed.
         * Currently 5 minutes (300,000 ms).
         */
        long ttlMs
) {

    /**
     * Well-known configuration: 100 requests/minute (spec default).
     */
    public static final RateLimit STANDARD = new RateLimit(
            100,        // capacity
            100,        // refillRatePerMinute
            5 * 60_000  // 5 minutes TTL
    );

    /**
     * Validation: ensure configuration makes sense.
     */
    public RateLimit {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (refillRatePerMinute <= 0) {
            throw new IllegalArgumentException(
                    "refillRatePerMinute must be positive: " + refillRatePerMinute
            );
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive: " + ttlMs);
        }
    }

    /**
     * Calculate tokens to refill based on elapsed time.
     *
     * FORMULA:
     * - refillRatePerMinute tokens are granted per 60,000 ms
     * - So per millisecond: refillRatePerMinute / 60,000
     * - After elapsedMs: (refillRatePerMinute / 60,000) * elapsedMs
     *
     * ROUNDING: We use integer arithmetic. Small fractional tokens are lost
     * (conservative approach - underestimates tokens available).
     *
     * Example:
     * - refillRatePerMinute = 100
     * - After 1 second (1000ms): (100 / 60,000) * 1000 = 1.67 tokens ≈ 1
     * - After 600ms: (100 / 60,000) * 600 = 1.0 token
     *
     * @param elapsedMs Milliseconds since last refill
     * @return Number of tokens to add to bucket
     */
    public long calculateRefill(long elapsedMs) {
        if (elapsedMs <= 0) {
            return 0;
        }
        return (refillRatePerMinute * elapsedMs) / 60_000;
    }

    /**
     * Calculate interval between token refills (in milliseconds).
     * Useful for scheduling workers or calculating retry-after.
     *
     * If capacity = 100 and refillRatePerMinute = 100:
     * - One token per 600ms (60,000 / 100)
     *
     * @return Milliseconds between single-token refills
     */
    public long getRefillIntervalMs() {
        return 60_000 / refillRatePerMinute;
    }
}