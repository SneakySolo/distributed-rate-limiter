package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.TestRedisContainer;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

public class LeakyBucketRateLimiterTest extends TestRedisContainer {

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
    public void testEnqueuesRequestWithinCapacity() {
        String userId = "user-queue";

        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");
            assertTrue(decision.allowed(), "Request " + i + " should be queued");
            assertNotNull(decision.requestId(), "Should have requestId");
        }
    }

    @Test
    public void testRejectsWhenQueueFull() {
        String userId = "user-full";

        for (int i = 0; i < 100; i++) {
            RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");
            assertTrue(decision.allowed(), "Should queue within capacity");
        }

        RateLimitDecision rejected = leakyBucket.tryConsume(userId, "payment");
        assertFalse(rejected.allowed());
    }

    @Test
    public void testIndependentUserQueues() {
        String user1 = "user-q1";
        String user2 = "user-q2";

        for (int i = 0; i < 100; i++) {
            leakyBucket.tryConsume(user1, "payment");
        }

        assertFalse(leakyBucket.tryConsume(user1, "payment").allowed());
        assertTrue(leakyBucket.tryConsume(user2, "payment").allowed());
    }

    @Test
    public void testRequestIdGeneration() {
        String userId = "user-id-test";

        RateLimitDecision d1 = leakyBucket.tryConsume(userId, "payment");
        RateLimitDecision d2 = leakyBucket.tryConsume(userId, "payment");

        assertNotNull(d1.requestId());
        assertNotNull(d2.requestId());
        assertNotEquals(d1.requestId(), d2.requestId());
    }

    @Test
    public void testConcurrentEnqueue() throws InterruptedException {
        String userId = "concurrent-queue";
        int threadCount = 10;
        int requestsPerThread = 15;

        Thread[] threads = new Thread[threadCount];
        int[] successCount = {0};
        Object lock = new Object();

        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");
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
    public void testQueueStorageInRedis() {
        String userId = "queue-storage";
        String queueKey = "lb:" + userId + ":payment:queue";

        leakyBucket.tryConsume(userId, "payment");

        Long queueSize = redisTemplate.opsForZSet().size(queueKey);
        assertEquals(1, queueSize);
    }

    @Test
    public void testRequestIdStatusStorage() {
        String userId = "status-storage";
        RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");

        String statusKey = "payment:" + decision.requestId() + ":status";
        String status = redisTemplate.opsForValue().get(statusKey);

        assertEquals("QUEUED", status);
    }

    @Test
    public void testIndependentServiceQueues() {
        String userId = "multi-service";

        for (int i = 0; i < 100; i++) {
            leakyBucket.tryConsume(userId, "payment");
        }

        assertFalse(leakyBucket.tryConsume(userId, "payment").allowed());
        assertTrue(leakyBucket.tryConsume(userId, "otp").allowed());
    }
}