package com.distributed.ratelimiter.integration;

import com.distributed.ratelimiter.TestRedisContainer;
import com.distributed.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.distributed.ratelimiter.algorithm.LeakyBucketRateLimiter;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class DistributedAtomicityTest extends TestRedisContainer {

    @Autowired
    private TokenBucketRateLimiter tokenBucket;

    @Autowired
    private LeakyBucketRateLimiter leakyBucket;

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
    public void testTokenBucketAtomicityWithHighConcurrency() throws InterruptedException {
        String userId = "tb-atomic-user";
        int threadCount = 20;
        int requestsPerThread = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        RateLimitDecision decision = tokenBucket.tryConsume(userId, "otp");
                        if (decision.allowed()) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        assertTrue(successCount.get() <= 100, "Should not exceed capacity");
        assertTrue(successCount.get() >= 95, "Should allow near-capacity requests");
    }

    @Test
    public void testLeakyBucketAtomicityWithHighConcurrency() throws InterruptedException {
        String userId = "lb-atomic-user";
        int threadCount = 20;
        int requestsPerThread = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");
                        if (decision.allowed()) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        assertTrue(successCount.get() <= 100, "Should not exceed queue capacity");
        assertTrue(successCount.get() >= 95, "Should queue near-capacity requests");
    }

    @Test
    public void testMultipleUsersIndependence() throws InterruptedException {
        int userCount = 5;
        int requestsPerUser = 20;
        CountDownLatch endLatch = new CountDownLatch(userCount);

        for (int u = 0; u < userCount; u++) {
            final int userId = u;
            new Thread(() -> {
                try {
                    for (int i = 0; i < requestsPerUser; i++) {
                        tokenBucket.tryConsume("user-" + userId, "otp");
                    }
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        endLatch.await();

        for (int u = 0; u < userCount; u++) {
            String key = "tb:user-" + u + ":otp";
            assertTrue(redisTemplate.hasKey(key), "Bucket should exist for user " + u);
        }
    }

    @Test
    public void testNoRaceConditionOnBucketRefill() throws InterruptedException {
        String userId = "refill-race";
        int iterations = 10;

        for (int iter = 0; iter < iterations; iter++) {
            redisTemplate.getConnectionFactory().getConnection().flushAll();

            int threadCount = 15;
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        RateLimitDecision decision = tokenBucket.tryConsume(userId, "otp");
                        if (decision.allowed()) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        endLatch.countDown();
                    }
                }).start();
            }

            endLatch.await();

            assertTrue(successCount.get() <= 100, "Iteration " + iter + " exceeded capacity");
        }
    }

    @Test
    public void testQueueOrderingWithMultipleThreads() throws InterruptedException {
        String userId = "queue-order";
        String queueKey = "lb:" + userId + ":payment:queue";
        int threadCount = 10;
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < 5; i++) {
                        leakyBucket.tryConsume(userId, "payment");
                    }
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        endLatch.await();

        Long finalSize = redisTemplate.opsForZSet().size(queueKey);
        assertEquals(50, finalSize, "All 50 requests should be in queue");
    }
}
