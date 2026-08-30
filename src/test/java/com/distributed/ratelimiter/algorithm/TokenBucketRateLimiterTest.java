package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.TestRedisContainer;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

public class TokenBucketRateLimiterTest extends TestRedisContainer {

    @Autowired
    private TokenBucketRateLimiter tokenBucket;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void cleanup() {
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    public void testAllowsRequestsWithinLimit() {
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = tokenBucket.tryConsume("user1", "otp");
            assertTrue(decision.allowed(), "Request " + i + " should be allowed");
            assertEquals(100 - i - 1, decision.remainingCapacity());
        }
    }

    @Test
    public void testRejectsWhenBucketExhausted() {
        String userId = "user-exhaust";

        for (int i = 0; i < 100; i++) {
            RateLimitDecision decision = tokenBucket.tryConsume(userId, "otp");
            assertTrue(decision.allowed(), "Should allow within capacity");
        }

        RateLimitDecision rejected = tokenBucket.tryConsume(userId, "otp");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterMillis() > 0);
    }

    @Test
    public void testIndependentUserLimits() {
        String user1 = "user-1";
        String user2 = "user-2";

        RateLimitDecision d1 = tokenBucket.tryConsume(user1, "otp");
        RateLimitDecision d2 = tokenBucket.tryConsume(user2, "otp");

        assertTrue(d1.allowed());
        assertTrue(d2.allowed());

        for (int i = 1; i < 100; i++) {
            tokenBucket.tryConsume(user1, "otp");
        }

        assertFalse(tokenBucket.tryConsume(user1, "otp").allowed());
        assertTrue(tokenBucket.tryConsume(user2, "otp").allowed());
    }

    @Test
    public void testIndependentServiceLimits() {
        String userId = "user-service";

        for (int i = 0; i < 100; i++) {
            tokenBucket.tryConsume(userId, "otp");
        }

        assertFalse(tokenBucket.tryConsume(userId, "otp").allowed());
        assertTrue(tokenBucket.tryConsume(userId, "payment").allowed());
    }

    @Test
    public void testConcurrentRequests() throws InterruptedException {
        String userId = "concurrent-user";
        int threadCount = 10;
        int requestsPerThread = 15;

        Thread[] threads = new Thread[threadCount];
        int[] successCount = {0};
        Object lock = new Object();

        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    RateLimitDecision decision = tokenBucket.tryConsume(userId, "otp");
                    if (decision.allowed()) {
                        synchronized (lock) {
                            successCount[0]++;
                        }
                    }
                }
            });
            threads[t].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        assertTrue(successCount[0] <= 100);
        assertTrue(successCount[0] >= 95);
    }

    @Test
    public void testRetryAfterCalculation() {
        String userId = "retry-test";

        for (int i = 0; i < 100; i++) {
            tokenBucket.tryConsume(userId, "otp");
        }

        RateLimitDecision rejected = tokenBucket.tryConsume(userId, "otp");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterMillis() > 0, "Retry after should be positive");
    }

    @Test
    public void testLazyInitialization() {
        String userId = "lazy-user";
        String key = "tb:" + userId + ":otp";

        Boolean existsBefore = redisTemplate.hasKey(key);
        tokenBucket.tryConsume(userId, "otp");
        Boolean existsAfter = redisTemplate.hasKey(key);

        assertFalse(existsBefore);
        assertTrue(existsAfter);
    }

    @Test
    public void testTtlExpiration() throws InterruptedException {
        String userId = "ttl-user";
        String key = "tb:" + userId + ":otp";

        tokenBucket.tryConsume(userId, "otp");
        assertTrue(redisTemplate.hasKey(key));

        Long ttl = redisTemplate.getExpire(key);
        assertTrue(ttl > 0, "TTL should be set");
    }
}
