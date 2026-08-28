package com.distributed.ratelimiter.service;

import com.distributed.ratelimiter.algorithm.LeakyBucketRateLimiter;
import com.distributed.ratelimiter.algorithm.RateLimiter;
import com.distributed.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RateLimit;
import com.distributed.ratelimiter.domain.RequestContext;
import com.distributed.ratelimiter.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service orchestration layer for rate limiting.
 *
 * RESPONSIBILITY:
 * - Determine which rate limiter algorithm to use based on route/service
 * - Instantiate and manage rate limiter instances
 * - Translate HTTP requests into domain RequestContext
 * - Route rate limit checks to appropriate algorithm
 * - Keep HTTP concerns separate from algorithm concerns
 *
 * DESIGN DECISION: Why a service layer?
 * - Controllers remain thin and focused on HTTP
 * - Algorithm selection logic is centralized and testable
 * - Easy to change routing rules without touching controllers
 * - Could extend with per-service config in future
 *
 * PHASE 1: Static routing
 * - "/otp/**" -> Token Bucket
 * - "/payment/**" -> Leaky Bucket
 *
 * In future phases, routing could be:
 * - Database-driven
 * - Per-tenant configuration
 * - Dynamic algorithm selection
 *
 * But for V1, static routing is simpler and sufficient.
 */
@Slf4j
@Service
public class RateLimiterService {

    /**
     * Token Bucket limiter instance (one per application).
     * In Phase 2, state moves to Redis but algorithm instance remains.
     */
    private final RateLimiter tokenBucketLimiter;

    /**
     * Leaky Bucket limiter instance (one per application).
     * In Phase 2, state moves to Redis but algorithm instance remains.
     */
    private final RateLimiter leakyBucketLimiter;

    /**
     * Constructor: instantiate both algorithms with standard config.
     *
     * DESIGN DECISION: Why instantiate both?
     * - Different endpoints (OTP vs Payment) use different algorithms
     * - Each algorithm maintains its own state
     * - Separation of concerns: each algorithm is independent
     * - Makes testing easier (can test each limiter in isolation)
     */

    @Autowired
    private RedisClient redisClient;

    public RateLimiterService(RedisClient redisClient) {
        this.tokenBucketLimiter = new TokenBucketRateLimiter(
                RateLimit.STANDARD,
                redisClient
        );

        // LeakyBucket: Only pass RateLimit (Phase 1 in-memory)
        this.leakyBucketLimiter = new LeakyBucketRateLimiter(
                RateLimit.STANDARD
        );

        log.info("✅ Initialized RateLimiterService");
        log.info("   - TokenBucket: Redis-backed (Phase 2)");
        log.info("   - LeakyBucket: In-memory (Phase 1, Redis coming in Phase 3)");
    }

    /**
     * Process a rate limit check for the OTP service.
     *
     * Uses Token Bucket algorithm (burst-friendly).
     *
     * @param userId User identifier from X-User-Id header
     * @param timestampMs Current timestamp
     * @return Rate limit decision (allowed/rejected with metadata)
     */
    public RateLimitDecision checkOtpRateLimit(String userId, long timestampMs) {
        RequestContext context = new RequestContext(userId, "otp", timestampMs);
        log.debug("Checking OTP rate limit for user: {}", userId);

        RateLimitDecision decision = tokenBucketLimiter.checkRateLimit(context);

        log.debug(
                "OTP rate limit decision for user {}: allowed={}, metadata={}",
                userId, decision.allowed(), decision.metadata()
        );

        return decision;
    }

    /**
     * Process a rate limit check for the Payment service.
     *
     * Uses Leaky Bucket algorithm (smoothing traffic for async processing).
     *
     * @param userId User identifier from X-User-Id header
     * @param timestampMs Current timestamp
     * @return Rate limit decision (allowed/rejected with metadata)
     *         If allowed, metadata contains requestId for status polling
     */
    public RateLimitDecision checkPaymentRateLimit(String userId, long timestampMs) {
        RequestContext context = new RequestContext(userId, "payment", timestampMs);
        log.debug("Checking Payment rate limit for user: {}", userId);

        RateLimitDecision decision = leakyBucketLimiter.checkRateLimit(context);

        log.debug(
                "Payment rate limit decision for user {}: allowed={}, metadata={}",
                userId, decision.allowed(), decision.metadata()
        );

        return decision;
    }

    /**
     * Get reference to Token Bucket limiter (for testing/resetting).
     */
    public RateLimiter getTokenBucketLimiter() {
        return tokenBucketLimiter;
    }

    /**
     * Get reference to Leaky Bucket limiter (for testing/resetting).
     */
    public RateLimiter getLeakyBucketLimiter() {
        return leakyBucketLimiter;
    }

    /**
     * Reset all rate limit state (useful for testing).
     * In production, this should not be called.
     */
    public void resetAll() {
        log.warn("Resetting all rate limit state - this should only happen in tests");
        tokenBucketLimiter.reset();
        leakyBucketLimiter.reset();
    }
}