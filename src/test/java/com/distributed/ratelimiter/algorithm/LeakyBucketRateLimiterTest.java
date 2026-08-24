package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RateLimit;
import com.distributed.ratelimiter.domain.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for LeakyBucketRateLimiter.
 *
 * TEST STRATEGY:
 * - Test queueing behavior (not immediate processing)
 * - Verify capacity limits
 * - Ensure independent queues per user+service
 * - Test edge cases (empty queue, full queue)
 *
 * IMPORTANT DESIGN NOTES:
 * - Leaky Bucket is QUEUE-based (not immediate consumption)
 * - Requests are accepted for async processing (202 response)
 * - Worker thread processes queue at fixed rate (separate phase)
 * - Phase 1: Just verify queueing behavior; worker added in Phase 3
 *
 * TEST CATEGORIES:
 * 1. Basic queueing: requests accepted to queue
 * 2. Capacity limits: queue full rejection
 * 3. Independent queues: per-user and per-service isolation
 * 4. Metadata: requestId tracking for status polling
 * 5. Rejection handling: proper retry-after times
 */
@DisplayName("LeakyBucketRateLimiter - Queue-Based Rate Limiting")
class LeakyBucketRateLimiterTest {

    private LeakyBucketRateLimiter limiter;
    private RateLimit config;

    @BeforeEach
    void setUp() {
        config = RateLimit.STANDARD; // 100 capacity queue
        limiter = new LeakyBucketRateLimiter(config);
    }

    // ========== BASIC QUEUEING TESTS ==========

    @Test
    @DisplayName("Should accept first request to queue")
    void testFirstRequestQueued() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act
        RateLimitDecision decision = limiter.checkRateLimit(context);

        // Assert
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingCapacity()).isEqualTo(1); // Queue depth after enqueue
        assertThat(decision.metadata()).contains("request_queued");
    }

    @Test
    @DisplayName("Should accept multiple requests to queue (until capacity)")
    void testMultipleRequestsQueued() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act: Queue up 50 requests
        for (int i = 0; i < 50; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(context);

            // Assert: Each should be queued
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remainingCapacity()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("Should reject when queue reaches capacity")
    void testQueueFullRejection() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act: Fill queue to capacity (100 requests)
        for (int i = 0; i < 100; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(context);
            assertThat(decision.allowed()).isTrue();
        }

        // Request 101 should be rejected
        RateLimitDecision rejected = limiter.checkRateLimit(context);

        // Assert
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.metadata()).contains("queue_full");
        assertThat(rejected.retryAfterMillis()).isPositive();
    }

    // ========== CAPACITY LIMIT TESTS ==========

    @Test
    @DisplayName("Should respect queue capacity of 100")
    void testQueueCapacityEnforcement() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act: Fill to capacity
        for (int i = 0; i < 100; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(context);
            assertThat(decision.allowed()).isTrue();
        }

        // 101st request should fail
        RateLimitDecision decision = limiter.checkRateLimit(context);

        // Assert
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    @DisplayName("Should handle exact capacity boundary")
    void testExactCapacityBoundary() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act: Queue exactly 100 (at capacity limit)
        RateLimitDecision lastAccepted = null;
        for (int i = 0; i < 100; i++) {
            lastAccepted = limiter.checkRateLimit(context);
        }

        // Assert: 100th request accepted
        assertThat(lastAccepted.allowed()).isTrue();
        assertThat(lastAccepted.remainingCapacity()).isEqualTo(100);

        // 101st request rejected
        RateLimitDecision rejected = limiter.checkRateLimit(context);
        assertThat(rejected.allowed()).isFalse();
    }

    // ========== INDEPENDENT QUEUES TESTS ==========

    @Test
    @DisplayName("Should maintain independent queues per user")
    void testIndependentQueuesPerUser() {
        // Arrange: Two users
        long timestamp = 1000;

        // Act: User 1 fills their queue to 100
        RequestContext user1Context = new RequestContext("user-1", "payment", timestamp);
        for (int i = 0; i < 100; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(user1Context);
            assertThat(decision.allowed()).isTrue();
        }

        // User 2 should have independent empty queue
        RequestContext user2Context = new RequestContext("user-2", "payment", timestamp);
        RateLimitDecision user2Decision = limiter.checkRateLimit(user2Context);

        // Assert: User 2 can still enqueue
        assertThat(user2Decision.allowed()).isTrue();
        assertThat(user2Decision.remainingCapacity()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should maintain independent queues per service for same user")
    void testIndependentQueuesPerService() {
        // Arrange
        long timestamp = 1000;

        // Act: User fills OTP queue (but we're testing payment, so this doesn't apply)
        // Let's test different services for same user

        RequestContext paymentContext1 = new RequestContext("user-1", "payment", timestamp);
        RequestContext otpContext1 = new RequestContext("user-1", "otp", timestamp);

        // Fill payment queue
        for (int i = 0; i < 100; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(paymentContext1);
            assertThat(decision.allowed()).isTrue();
        }

        // OTP should have independent queue (won't use it with Leaky Bucket in real life,
        // but testing isolation)
        RateLimitDecision otpDecision = limiter.checkRateLimit(otpContext1);

        // Assert: OTP queue is independent and empty
        assertThat(otpDecision.allowed()).isTrue();
        assertThat(otpDecision.remainingCapacity()).isEqualTo(1);
    }

    // ========== METADATA AND REQUESTID TESTS ==========

    @Test
    @DisplayName("Should include requestId in metadata for status polling")
    void testRequestIdTracking() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act
        RateLimitDecision decision = limiter.checkRateLimit(context);

        // Assert: Metadata should contain requestId
        assertThat(decision.metadata()).contains("request_queued:");
        // Extract requestId (format: "request_queued:requestId")
        String requestId = decision.metadata().split(":")[1];
        assertThat(requestId).isNotEmpty();
    }

    @Test
    @DisplayName("Should generate unique requestIds for each enqueue")
    void testUniqueRequestIds() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act: Queue multiple requests
        String requestId1 = limiter.checkRateLimit(context)
                .metadata().split(":")[1];
        String requestId2 = limiter.checkRateLimit(context)
                .metadata().split(":")[1];

        // Assert: IDs should be different
        assertThat(requestId1).isNotEqualTo(requestId2);
    }

    // ========== RETRY-AFTER CALCULATION TESTS ==========

    @Test
    @DisplayName("Should calculate retry-after for full queue")
    void testRetryAfterForFullQueue() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Fill queue
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context);
        }

        // Act: Try to enqueue when full
        RateLimitDecision decision = limiter.checkRateLimit(context);

        // Assert: Retry-after should be one leak interval (600ms for 100/min)
        assertThat(decision.allowed()).isFalse();
        long expectedRetryAfter = config.getRefillIntervalMs();
        assertThat(decision.retryAfterMillis()).isEqualTo(expectedRetryAfter);
    }

    // ========== TIME INDEPENDENCE TESTS ==========

    @Test
    @DisplayName("Should not depend on request timestamp for queueing")
    void testTimeIndependence() {
        // Arrange: Two requests with different timestamps

        // Act: Request at t=1000
        RequestContext context1 = new RequestContext("user-1", "payment", 1000);
        RateLimitDecision decision1 = limiter.checkRateLimit(context1);

        // Request at t=2000 (time passed, but queue doesn't care)
        RequestContext context2 = new RequestContext("user-1", "payment", 2000);
        RateLimitDecision decision2 = limiter.checkRateLimit(context2);

        // Assert: Both queued (queueing doesn't depend on time, only capacity)
        assertThat(decision1.allowed()).isTrue();
        assertThat(decision2.allowed()).isTrue();
        assertThat(decision2.remainingCapacity()).isEqualTo(2);
    }

    // ========== RESET FUNCTIONALITY TESTS ==========

    @Test
    @DisplayName("Should reset all queue state")
    void testReset() {
        // Arrange: Fill queue
        RequestContext context = new RequestContext("user-1", "payment", 1000);
        for (int i = 0; i < 100; i++) {
            limiter.checkRateLimit(context);
        }

        // Verify full
        RateLimitDecision beforeReset = limiter.checkRateLimit(context);
        assertThat(beforeReset.allowed()).isFalse();

        // Act: Reset
        limiter.reset();

        // Assert: Queue should be empty again
        RateLimitDecision afterReset = limiter.checkRateLimit(context);
        assertThat(afterReset.allowed()).isTrue();
        assertThat(afterReset.remainingCapacity()).isEqualTo(1);
    }

    // ========== EDGE CASES ==========

    @Test
    @DisplayName("Should handle single request")
    void testSingleRequest() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act
        RateLimitDecision decision = limiter.checkRateLimit(context);

        // Assert
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingCapacity()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle rapid sequential requests (same timestamp)")
    void testRapidSequentialRequests() {
        // Arrange
        long timestamp = 1000;
        RequestContext context = new RequestContext("user-1", "payment", timestamp);

        // Act: 5 rapid requests at same timestamp
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(context);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remainingCapacity()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("Should enforce capacity with concurrent-like requests")
    void testCapacityWithManyRequests() {
        // Arrange
        RequestContext context = new RequestContext("user-1", "payment", 1000);

        // Act: Make 150 requests (more than capacity)
        int accepted = 0;
        int rejected = 0;

        for (int i = 0; i < 150; i++) {
            RateLimitDecision decision = limiter.checkRateLimit(context);
            if (decision.allowed()) {
                accepted++;
            } else {
                rejected++;
            }
        }

        // Assert: Exactly 100 accepted, 50 rejected
        assertThat(accepted).isEqualTo(100);
        assertThat(rejected).isEqualTo(50);
    }
}