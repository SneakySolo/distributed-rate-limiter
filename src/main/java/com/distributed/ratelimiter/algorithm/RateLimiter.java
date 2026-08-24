package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RequestContext;
/**
 * Core abstraction for rate limiting algorithms.
 *
 * DESIGN DECISION: Why an interface?
 * - Allows different rate limiting strategies (Token Bucket, Leaky Bucket) to coexist
 * - Lets HTTP layer remain agnostic to algorithm details
 * - Makes testing easier (mock implementations)
 * - Enables clean plugin-style algorithm selection
 *
 * IMPORTANT: This is NOT over-engineering. Each implementation has distinct logic
 * and state management. The interface provides meaningful abstraction.
 */
public interface RateLimiter {

    /**
     * Process a rate limit check for the given request context.
     *
     * MUST be thread-safe. In Phase 1, this is single-threaded.
     * In Phase 2+, Redis atomicity guarantees thread safety.
     *
     * @param context The request context (user ID, service, timestamp)
     * @return A decision containing: allowed/rejected, retry-after time, remaining capacity
     */
    RateLimitDecision checkRateLimit(RequestContext context);

    /**
     * Get the algorithm name for logging/identification.
     * @return Algorithm identifier (e.g., "TOKEN_BUCKET", "LEAKY_BUCKET")
     */
    String getAlgorithmName();

    /**
     * Soft reset for testing purposes (clears in-memory state).
     * In Phase 1, this clears the algorithm's internal state.
     * In Phase 2+, this would clear Redis state.
     */
    void reset();
}
