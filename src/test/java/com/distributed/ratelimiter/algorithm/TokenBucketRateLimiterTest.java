package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RateLimit;
import com.distributed.ratelimiter.domain.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TokenBucketRateLimiter.
 *
 * TEST STRATEGY:
 * - Test core algorithm behavior in isolation
 * - No external dependencies (Redis, network, etc.)
 * - Focus on correctness of token consumption and refill
 * - Verify edge cases and boundary conditions
 *
 * TEST CATEGORIES:
 * 1. Basic functionality: allow/reject decisions
 * 2. Refill calculation: tokens added over time
 * 3. Burst handling: multiple requests in quick succession
 * 4. Capacity limits: cannot exceed max tokens
 * 5. Time progression: refill only after enough time elapsed
 */
@DisplayName("TokenBucketRateLimiter - Core Algorithm")
class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;
    private RateLimit config;

    @BeforeEach
    void setUp() {
        config = RateLimit.STANDARD; // 100 capacity, 100/min refill, 5min TTL
        limiter = new TokenBucketRateLimiter(config);
    }

    // ========== BASIC FUNCTIONALITY TESTS ==========

    @Test
    @DisplayName("Should allow first request (bucket initialized full)")
    void testFirstRequestAllowed() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "otp", 1000);

        // Act
        RateLimitDecision decision = limiter.checkRateLimit(context);

        // Assert
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingCapacity()).isEqualTo(99); // 100 - 1
        assertThat(decision.retryAfterMillis()).isZero();
    }

    @Test
    @DisplayName("Should allow multiple requests up to capacity (burst)")
    void testBurstAllowed() {
        // Arrange
        String userId = "user-1";
        String service = "otp";
        long timestamp = 1000;

        // Act: Make 100 requests instantly (use all tokens)
        for (int i = 0; i < 100; i++) {
            RequestContext context = new RequestContext(userId, service, timestamp);
            RateLimitDecision decision = limiter.checkRateLimit(context);

            // Assert each is allowed
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remainingCapacity()).isEqualTo(99 - i);
        }

        // Request 101 should be rejected (no tokens)
        RequestContext context = new RequestContext(userId, service, timestamp);
        RateLimitDecision decision = limiter.checkRateLimit(context);
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    @DisplayName("Should reject when bucket empty")
    void testRejectionWhenEmpty() {
        // Arrange: Exhaust all tokens
        RequestContext context1 = new RequestContext("user-1", "otp", 1000);
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context1);
        }

        // Act: Try one more without time passing (no refill)
        RateLimitDecision decision = limiter.checkRateLimit(context1);

        // Assert
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMillis()).isPositive();
        assertThat(decision.metadata()).contains("no_tokens");
    }

    // ========== REFILL CALCULATION TESTS ==========

    @Test
    @DisplayName("Should refill tokens after time elapsed")
    void testRefillAfterTimeElapsed() {
        // Arrange
        RequestContext context1 = new RequestContext("user-1", "otp", 1000);

        // Consume all tokens at t=1000
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context1);
        }

        // Verify bucket empty
        RateLimitDecision afterExhaust = limiter.checkRateLimit(context1);
        assertThat(afterExhaust.allowed()).isFalse();

        // Act: Time passes 600ms (enough for 1 token to refill at 100/min)
        // Refill = (100 tokens/min) / (60_000 ms/min) * 600ms = 1 token
        RequestContext context2 = new RequestContext("user-1", "otp", 1600);
        RateLimitDecision decision = limiter.checkRateLimit(context2);

        // Assert: 1 token refilled, consumed by this request
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingCapacity()).isZero();
        assertThat(decision.metadata()).contains("token_available");
    }

    @Test
    @DisplayName("Should cap tokens at capacity (no overfill)")
    void testCapacityLimit() {
        // Arrange
        RequestContext context1 = new RequestContext("user-1", "otp", 1000);

        // Consume 50 tokens (leaving 50)
        for (int i = 0; i < 50; i++) {
            limiter.checkRateLimit(context1);
        }

        // Act: Wait 1 minute (refills 100 tokens, but cap at 100 total)
        RequestContext context2 = new RequestContext("user-1", "otp", 1000 + 60_000);
        RateLimitDecision decision = limiter.checkRateLimit(context2);

        // Assert: Should have 100 (not 50+100=150), minus 1 for this request
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingCapacity()).isEqualTo(99);
    }

    @Test
    @DisplayName("Should refill proportionally to elapsed time")
    void testProportionalRefill() {
        // Arrange
        RequestContext context1 = new RequestContext("user-1", "otp", 1000);

        // Consume all tokens
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context1);
        }

        // Act: Wait 3 seconds (3 tokens refilled at 100/min ≈ 1.67/sec)
        // (100 / 60,000) * 3000 = 5 tokens
        RequestContext context2 = new RequestContext("user-1", "otp", 1000 + 3_000);

        // Make 5 requests to consume the refilled tokens
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(context2);
            assertThat(decision.allowed()).isTrue();
        }

        // 6th request should be rejected (no more refilled tokens)
        RateLimitDecision rejected = limiter.checkRateLimit(context2);
        assertThat(rejected.allowed()).isFalse();
    }

    // ========== INDEPENDENT BUCKETS TESTS ==========

    @Test
    @DisplayName("Should maintain independent buckets per user+service")
    void testIndependentBuckets() {
        // Arrange: Two different users
        long timestamp = 1000;

        // Act: User 1 exhausts their bucket
        RequestContext user1Context = new RequestContext("user-1", "otp", timestamp);
        for (int i = 0; i < 100; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(user1Context);
            assertThat(decision.allowed()).isTrue();
        }

        // User 2 should still have full bucket
        RequestContext user2Context = new RequestContext("user-2", "otp", timestamp);
        RateLimitDecision user2Decision = limiter.checkRateLimit(user2Context);

        // Assert
        assertThat(user2Decision.allowed()).isTrue();
        assertThat(user2Decision.remainingCapacity()).isEqualTo(99);
    }

    @Test
    @DisplayName("Should maintain independent buckets per service for same user")
    void testIndependentBucketsPerService() {
        // Arrange
        long timestamp = 1000;

        // Act: User exhausts OTP bucket
        RequestContext otpContext = new RequestContext("user-1", "otp", timestamp);
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(otpContext);
        }

        // Same user should have independent PAYMENT bucket
        RequestContext paymentContext = new RequestContext("user-1", "payment", timestamp);
        RateLimitDecision paymentDecision = limiter.checkRateLimit(paymentContext);

        // Assert
        assertThat(paymentDecision.allowed()).isTrue();
        assertThat(paymentDecision.remainingCapacity()).isEqualTo(99);
    }

    // ========== RETRY-AFTER CALCULATION TESTS ==========

    @Test
    @DisplayName("Should calculate correct retry-after time")
    void testRetryAfterCalculation() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "otp", 1000);

        // Exhaust bucket
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context);
        }

        // Act: Request when empty
        RateLimitDecision decision = limiter.checkRateLimit(context);

        // Assert: Retry-after should be one refill interval (600ms for 100/min)
        assertThat(decision.allowed()).isFalse();
        long expectedRetryAfter = config.getRefillIntervalMs();
        assertThat(decision.retryAfterMillis()).isEqualTo(expectedRetryAfter);
    }

    // ========== RESET FUNCTIONALITY TESTS ==========

    @Test
    @DisplayName("Should reset all bucket state")
    void testReset() {
        // Arrange: Exhaust bucket
        RequestContext context = new RequestContext("user-1", "otp", 1000);
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context);
        }

        // Act: Reset
        limiter.reset();

        // Assert: Bucket should be refilled
        RateLimitDecision decision = limiter.checkRateLimit(context);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingCapacity()).isEqualTo(99);
    }

    // ========== EDGE CASES ==========

    @Test
    @DisplayName("Should handle zero elapsed time (same timestamp)")
    void testZeroElapsedTime() {
        // Arrange
        long timestamp = 1000;
        RequestContext context = new RequestContext("user-1", "otp", timestamp);

        // Act: Multiple requests at same timestamp (no time passes)
        RateLimitDecision d1 = limiter.checkRateLimit(context);
        RateLimitDecision d2 = limiter.checkRateLimit(context);

        // Assert: Both should succeed (no refill between them, but both use existing tokens)
        assertThat(d1.allowed()).isTrue();
        assertThat(d2.allowed()).isTrue();
    }

    @Test
    @DisplayName("Should handle large time jumps")
    void testLargeTimeJump() {
        // Arrange
        RequestContext context1 = new RequestContext("user-1", "otp", 1000);

        // Consume all tokens
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context1);
        }

        // Act: Jump 10 minutes (refills 1000 tokens, but capped at 100)
        RequestContext context2 = new RequestContext("user-1", "otp", 1000 + (10 * 60_000));
        RateLimitDecision decision = limiter.checkRateLimit(context2);

        // Assert: Should have capacity limit enforced
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingCapacity()).isEqualTo(99); // 100 cap - 1 consumed
    }
}