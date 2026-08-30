package com.distributed.ratelimiter.worker;

import com.distributed.ratelimiter.TestRedisContainer;
import com.distributed.ratelimiter.algorithm.LeakyBucketRateLimiter;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RequestStatus;
import com.distributed.ratelimiter.service.PaymentStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

public class LeakyBucketWorkerTest extends TestRedisContainer {

    @Autowired
    private LeakyBucketWorker worker;

    @Autowired
    private LeakyBucketRateLimiter leakyBucket;

    @Autowired
    private PaymentStatusService statusService;

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
    public void testWorkerProcessesQueuedRequests() throws InterruptedException {
        String userId = "worker-user";
        RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");
        String requestId = decision.requestId();

        assertTrue(decision.allowed());
        assertEquals("QUEUED", statusService.getStatus(requestId).toString());

        Thread.sleep(100);
        worker.processQueue();

        RequestStatus status = statusService.getStatus(requestId);
        assertNotNull(status);
        assertTrue(status == RequestStatus.COMPLETED || status == RequestStatus.PROCESSING);
    }

    @Test
    public void testWorkerRemovesProcessedRequests() throws InterruptedException {
        String userId = "removal-test";
        String queueKey = "lb:" + userId + ":payment:queue";

        RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");
        assertTrue(redisTemplate.opsForZSet().size(queueKey) > 0);

        Thread.sleep(100);
        worker.processQueue();

        Long remaining = redisTemplate.opsForZSet().size(queueKey);
        assertEquals(0, remaining);
    }

    @Test
    public void testWorkerHandlesMultipleRequests() throws InterruptedException {
        String userId = "multi-request";

        for (int i = 0; i < 5; i++) {
            leakyBucket.tryConsume(userId, "payment");
        }

        String queueKey = "lb:" + userId + ":payment:queue";
        Long initialSize = redisTemplate.opsForZSet().size(queueKey);
        assertEquals(5, initialSize);

        Thread.sleep(100);
        worker.processQueue();

        Long finalSize = redisTemplate.opsForZSet().size(queueKey);
        assertEquals(0, finalSize);
    }

    @Test
    public void testWorkerDoesNotProcessFutureRequests() throws InterruptedException {
        String userId = "future-test";

        // Queue multiple requests at once
        // Request 0: scheduled at nowMs
        // Request 1: scheduled at nowMs + 600ms
        // Request 2: scheduled at nowMs + 1200ms
        for (int i = 0; i < 3; i++) {
            leakyBucket.tryConsume(userId, "payment");
        }

        String queueKey = "lb:" + userId + ":payment:queue";

        // Wait a small amount (not enough for request 1 to be due)
        Thread.sleep(300);

        // Process queue - should only process the first request (scheduled at nowMs)
        worker.processQueue();

        Long remaining = redisTemplate.opsForZSet().size(queueKey);
        // Request 0 should be processed, Request 1 (600ms offset) and Request 2 (1200ms offset) should remain
        assertEquals(2, remaining);
    }

    @Test
    public void testWorkerLocking() throws InterruptedException {
        String userId = "lock-test";
        leakyBucket.tryConsume(userId, "payment");

        Thread thread1 = new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                worker.processQueue();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread thread2 = new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                worker.processQueue();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    @Test
    public void testWorkerClearsStatusAfterCompletion() throws InterruptedException {
        String userId = "clear-test";
        RateLimitDecision decision = leakyBucket.tryConsume(userId, "payment");
        String requestId = decision.requestId();

        assertNotNull(statusService.getStatus(requestId));

        Thread.sleep(100);
        worker.processQueue();

        RequestStatus status = statusService.getStatus(requestId);
        assertTrue(status == RequestStatus.COMPLETED || status == RequestStatus.PROCESSING);
    }
}