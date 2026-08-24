package com.distributed.ratelimiter.domain;

/**
 * Immutable decision result from a rate limiter check.
 *
 * DESIGN DECISION: Why a record?
 * Records in Java 21 provide:
 * - Immutability by design (val safety)
 * - Auto-generated hashCode, equals, toString
 * - Compact, clear data transfer object
 * - No need for Lombok boilerplate
 *
 * IMPORTANT: This decouples the algorithm layer from HTTP layer.
 * The algorithm returns a domain decision, not HTTP responses.
 * Controllers then translate this to HTTP status codes.
 */
public record RateLimitDecision(
        /**
         * Was the request allowed? true = 200/202, false = 429
         */
        boolean allowed,

        /**
         * If rejected, how many milliseconds should client wait before retrying?
         * Only meaningful if allowed = false.
         */
        long retryAfterMillis,

        /**
         * For Token Bucket: tokens remaining in bucket after this request.
         * For Leaky Bucket: current queue depth.
         * For rejected requests: 0.
         */
        long remainingCapacity,

        /**
         * Algorithm-specific metadata for debugging/observability.
         * Examples: "bucket_refilled", "queue_full", "processing_delay_ms"
         */
        String metadata
) {

    /**
     * Convenience constructor for allowed requests.
     */
    public static RateLimitDecision allowed(long remainingCapacity, String metadata) {
        return new RateLimitDecision(true, 0, remainingCapacity, metadata);
    }

    /**
     * Convenience constructor for rejected requests.
     *
     * @param retryAfterMillis How long client should wait before retrying
     * @param metadata Reason for rejection
     */
    public static RateLimitDecision rejected(long retryAfterMillis, String metadata) {
        return new RateLimitDecision(false, retryAfterMillis, 0, metadata);
    }

    /**
     * Validation: ensure decision is internally consistent.
     */
    public RateLimitDecision {
        if (!allowed && retryAfterMillis <= 0) {
            throw new IllegalArgumentException(
                    "Rejected decisions must have positive retryAfterMillis: " + retryAfterMillis
            );
        }
        if (allowed && retryAfterMillis != 0) {
            throw new IllegalArgumentException(
                    "Allowed decisions must have zero retryAfterMillis"
            );
        }
    }
}