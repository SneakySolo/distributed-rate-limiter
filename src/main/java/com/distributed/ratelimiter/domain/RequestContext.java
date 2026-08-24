package com.distributed.ratelimiter.domain;

/**
 * Immutable context for a single rate limit check.
 *
 * DESIGN DECISION: Why extract request data into a record?
 * - Decouples algorithm logic from HTTP concerns (Spring Request objects)
 * - Makes algorithms testable without mocking HTTP layer
 * - Clearly documents what rate limiter needs to know
 * - Enables future extensibility (add fields without refactoring algorithms)
 */
public record RequestContext(
        /**
         * User identifier from X-User-Id header.
         * This is the identity for which rate limit is enforced.
         */
        String userId,

        /**
         * Service being accessed. In Phase 1: "otp" or "payment".
         * Rate limit is per-user + per-service (independent buckets).
         */
        String service,

        /**
         * Current timestamp in milliseconds (System.currentTimeMillis()).
         * Used for refill calculation in Token Bucket, scheduling in Leaky Bucket.
         */
        long timestampMs
) {

    /**
     * Validation: ensure required fields are present.
     */
    public RequestContext {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service cannot be null or blank");
        }
        if (timestampMs <= 0) {
            throw new IllegalArgumentException("timestampMs must be positive: " + timestampMs);
        }
    }

    /**
     * Generate a composite key for this request's rate limit state.
     * Used in Phase 1 for in-memory storage, Phase 2+ for Redis keys.
     *
     * Example: "ratelimit:user-123:otp"
     *
     * @return Redis-safe composite key
     */
    public String getRateLimitKey() {
        return "ratelimit:" + userId + ":" + service;
    }
}