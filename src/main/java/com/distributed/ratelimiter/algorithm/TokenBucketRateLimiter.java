package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RateLimit;
import com.distributed.ratelimiter.domain.RequestContext;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Token Bucket rate limiter - Phase 1 in-memory implementation.
 *
 * ALGORITHM OVERVIEW:
 * ==================
 * A "bucket" holds tokens. Each request consumes 1 token.
 * Tokens refill over time at a fixed rate.
 *
 * If tokens available: REQUEST ALLOWED
 * If no tokens: REQUEST REJECTED (tell client to retry after time T)
 *
 * BEHAVIOR:
 * - Burst-friendly: All available tokens can be consumed immediately
 * - Example: 100-token bucket refilled at 100/min
 *   - First 100 requests allowed instantly
 *   - Request 101 rejected, retry after 600ms (1 token refill time)
 *
 * REFILL CALCULATION (for Phase 2+ Lua script):
 * - lastRefillTimeMs: when we last updated the bucket
 * - currentTimeMs: now
 * - elapsedMs = currentTimeMs - lastRefillTimeMs
 * - tokensToAdd = (refillRatePerMinute / 60,000) * elapsedMs
 * - newTokens = min(capacity, oldTokens + tokensToAdd)
 *
 * STATE PER BUCKET (Phase 1 in-memory):
 * - tokens: current token count (capped at capacity)
 * - lastRefillTimeMs: timestamp of last refill
 *
 * PHASE 1 IMPORTANT:
 * - Single-threaded safety: This implementation is NOT thread-safe.
 *   We use ConcurrentHashMap for storage, but the refill+consume logic
 *   itself is not atomic.
 * - In Phase 2, Redis + Lua will make this atomic across all instances.
 *
 * DESIGN DECISION: Why separate bucket state class?
 * - Keeps mutable state isolated
 * - Clear what we're tracking per rate limit
 * - Makes it easier to migrate to Redis in Phase 2
 */
@Slf4j
public class TokenBucketRateLimiter implements RateLimiter {

    /**
     * Mutable bucket state (tokens and last refill time).
     * In Phase 1, stored in-memory. In Phase 2, stored in Redis.
     */
    private static class BucketState {
        volatile long tokens;
        volatile long lastRefillTimeMs;

        BucketState(long initialTokens, long timestampMs) {
            this.tokens = initialTokens;
            this.lastRefillTimeMs = timestampMs;
        }
    }

    private final RateLimit config;

    /**
     * In-memory storage: key -> bucket state.
     * Key format: "ratelimit:{userId}:{service}"
     *
     * In Phase 2, this is replaced with Redis.
     * ConcurrentHashMap chosen for simplicity; actual concurrency
     * control happens at the algorithm level (refill+consume must be atomic).
     */
    private final ConcurrentMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(RateLimit config) {
        this.config = config;
    }

    /**
     * Main rate limit check: refill bucket and consume one token.
     *
     * STEP-BY-STEP:
     * 1. Get or create bucket state
     * 2. Calculate elapsed time since last refill
     * 3. Add refilled tokens (capped at capacity)
     * 4. If tokens > 0: consume 1, return ALLOWED
     * 5. If tokens = 0: return REJECTED with retry-after time
     *
     * THREAD-SAFETY NOTE (Phase 1):
     * This logic is NOT atomic. In a multi-threaded scenario:
     * - Thread A and B might read same bucket state
     * - Both calculate refill independently
     * - Race condition on token consumption
     *
     * PHASE 2 FIX: Redis Lua script makes entire operation atomic
     * on the server side, eliminating race conditions across
     * all three application instances.
     */
    @Override
    public synchronized RateLimitDecision checkRateLimit(RequestContext context) {
        String key = context.getRateLimitKey();
        long now = context.timestampMs();

        // Step 1: Get or create bucket
        BucketState bucket = buckets.computeIfAbsent(
                key,
                str -> {
                    log.debug("Initializing new bucket for key: {}", key);
                    return new BucketState(config.capacity(), now);
                }
        );

        // Step 2: Calculate refill
        long elapsedMs = now - bucket.lastRefillTimeMs;
        long tokensToAdd = config.calculateRefill(elapsedMs);

        if (tokensToAdd > 0) {
            log.debug(
                    "Refilling bucket {}: elapsed={}ms, adding {} tokens",
                    key, elapsedMs, tokensToAdd
            );
            bucket.tokens = Math.min(config.capacity(), bucket.tokens + tokensToAdd);
            bucket.lastRefillTimeMs = now;
        }

        // Step 3: Attempt consumption
        if (bucket.tokens > 0) {
            bucket.tokens--;
            log.debug("Token consumed for {}. Remaining: {}", key, bucket.tokens);
            return RateLimitDecision.allowed(
                    bucket.tokens,
                    "token_available"
            );
        } else {
            // Step 4: No tokens - rejected
            long retryAfterMs = config.getRefillIntervalMs();
            log.warn(
                    "Rate limit exceeded for {}. Rejecting request. Retry after {}ms",
                    key, retryAfterMs
            );
            return RateLimitDecision.rejected(
                    retryAfterMs,
                    "no_tokens_available"
            );
        }
    }

    @Override
    public String getAlgorithmName() {
        return "TOKEN_BUCKET";
    }

    /**
     * Reset all buckets (useful for testing).
     * In Phase 2, this would clear Redis state.
     */
    @Override
    public void reset() {
        log.info("Resetting all Token Bucket state");
        buckets.clear();
    }

    /**
     * Debug method: get bucket state (for testing).
     */
    protected BucketState getBucketState(String key) {
        return buckets.get(key);
    }
}
