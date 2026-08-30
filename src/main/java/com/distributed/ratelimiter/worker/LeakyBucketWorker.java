package com.distributed.ratelimiter.worker;

import com.distributed.ratelimiter.config.RateLimiterConfig;
import com.distributed.ratelimiter.domain.RequestStatus;
import com.distributed.ratelimiter.service.PaymentStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class LeakyBucketWorker {

    private static final Logger log = LoggerFactory.getLogger(LeakyBucketWorker.class);
    private static final String WORKER_LOCK_KEY = "worker:lock";
    private static final long LOCK_DURATION_MS = 1000;

    private final RedisTemplate<String, String> redisTemplate;
    private final PaymentStatusService statusService;
    private final RateLimiterConfig config;
    private final String workerId = UUID.randomUUID().toString();

    public LeakyBucketWorker(RedisTemplate<String, String> redisTemplate,
                             PaymentStatusService statusService,
                             RateLimiterConfig config) {
        this.redisTemplate = redisTemplate;
        this.statusService = statusService;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${ratelimiter.leakyBucket.workerIntervalMillis:600}")
    public void processQueue() {
        if (!acquireLock()) {
            return;
        }

        try {
            long nowMs = fetchRedisTimeMs();

            // Fetch due requests from all queues (scan for queue keys)
            Set<String> queueKeys = redisTemplate.keys("lb:*:payment:queue");
            if (queueKeys == null || queueKeys.isEmpty()) {
                return;
            }

            for (String queueKey : queueKeys) {
                processSingleQueue(queueKey, nowMs);
            }
        } catch (Exception e) {
            log.error("Error processing leaky bucket queue", e);
        } finally {
            releaseLock();
        }
    }

    private void processSingleQueue(String queueKey, long nowMs) {
        // Get requests with score <= now (requests due now or earlier)
        Set<String> dueRequests = redisTemplate.opsForZSet()
                .rangeByScore(queueKey, Double.NEGATIVE_INFINITY, nowMs);

        if (dueRequests == null || dueRequests.isEmpty()) {
            return;
        }

        for (String requestId : dueRequests) {
            processRequest(requestId, queueKey);
        }
    }

    private void processRequest(String requestId, String queueKey) {
        try {
            // Update status to PROCESSING
            statusService.setStatus(requestId, RequestStatus.PROCESSING);

            // Simulate payment processing
            simulatePaymentProcessing();

            // Mark as completed
            statusService.setStatus(requestId, RequestStatus.COMPLETED);

            // Remove from queue
            redisTemplate.opsForZSet().remove(queueKey, requestId);

            log.debug("Completed payment request: {}", requestId);
        } catch (Exception e) {
            log.error("Failed to process request: {}", requestId, e);
            statusService.setStatus(requestId, RequestStatus.FAILED);
        }
    }

    private void simulatePaymentProcessing() {
        // Simulate processing time
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean acquireLock() {
        String lockValue = workerId + ":" + System.currentTimeMillis();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(WORKER_LOCK_KEY, lockValue,
                        java.time.Duration.ofMillis(LOCK_DURATION_MS));
        return acquired != null && acquired;
    }

    private void releaseLock() {
        redisTemplate.delete(WORKER_LOCK_KEY);
    }

    private long fetchRedisTimeMs() {
        return System.currentTimeMillis();
    }
}