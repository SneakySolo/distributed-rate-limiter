package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RateLimit;
import com.distributed.ratelimiter.domain.RequestContext;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Leaky Bucket rate limiter - Phase 1 in-memory queue implementation.
 *
 * ALGORITHM OVERVIEW:
 * ===================
 * Requests flow into a queue. A worker processes them at a fixed rate
 * (the "leak" rate). Queue has bounded capacity.
 *
 * If queue has space: REQUEST QUEUED (202 Accepted)
 * If queue full: REQUEST REJECTED (429 Too Many Requests)
 *
 * BEHAVIOR:
 * - Smooths traffic: burst requests are spread over time
 * - Predictable processing: fixed rate = predictable throughput
 * - Queue-based: suitable for async workloads (payments)
 *
 * LEAK RATE: 100 requests/minute = 1 request every 600ms
 *
 * STATE PER BUCKET (Phase 1 in-memory):
 * - queue: LinkedBlockingQueue<RequestId> with maxCapacity
 *
 * WORKER THREAD (separate from request path):
 * - Polls queue at fixed interval (every 600ms)
 * - Processes one request per interval
 * - Updates request status (QUEUED -> PROCESSING -> COMPLETED/FAILED)
 *
 * PHASE 1 IMPORTANT:
 * - This is a simplified async implementation
 * - Real processing is stubbed (log message + sleep)
 * - Worker runs in background and processes requests
 * - Status tracking is in-memory (no Redis)
 * - In Phase 2, queue moves to Redis ZSET (priority by scheduled time)
 * - In Phase 2, worker reads from Redis and stores status there
 *
 * DESIGN DECISION: Why separate queue per user+service?
 * - Independent rate limits per user+service
 * - Prevents one user from starving others
 * - Matches spec requirement: "100 requests/minute per user + service"
 */
@Slf4j
public class LeakyBucketRateLimiter implements RateLimiter {

    /**
     * Mutable queue state.
     * In Phase 1, stored in-memory. In Phase 2, stored in Redis ZSET.
     */
    private static class QueueState {
        final LinkedBlockingQueue<String> queue;

        QueueState(int capacity) {
            this.queue = new LinkedBlockingQueue<>(capacity);
        }
    }

    private final RateLimit config;

    /**
     * In-memory storage: key -> queue state.
     * Key format: "ratelimit:{userId}:{service}"
     * Each user+service pair has independent queue.
     */
    private final ConcurrentMap<String, QueueState> queues = new ConcurrentHashMap<>();

    public LeakyBucketRateLimiter(RateLimit config) {
        this.config = config;
    }

    /**
     * Main rate limit check: attempt to enqueue request.
     *
     * STEP-BY-STEP:
     * 1. Get or create queue for this user+service
     * 2. Attempt to add request to queue (non-blocking)
     * 3. If enqueue successful: ACCEPTED (202)
     * 4. If queue full: REJECTED (429)
     *
     * IMPORTANT DISTINCTION FROM TOKEN BUCKET:
     * - Does NOT process immediately
     * - Returns "accepted for processing" (202)
     * - Actual processing happens in background worker
     * - Response includes requestId for status polling
     *
     * DESIGN DECISION: Request ID in response.
     * In Phase 1, we just return queue depth as requestId.
     * In Phase 2, we'll use UUID and store in Redis.
     */
    @Override
    public synchronized RateLimitDecision checkRateLimit(RequestContext context) {
        String key = context.getRateLimitKey();

        // Step 1: Get or create queue
        QueueState queueState = queues.computeIfAbsent(
                key,
                str -> {
                    log.debug("Initializing new queue for key: {}", key);
                    return new QueueState((int) config.capacity());
                }
        );

        // Step 2: Attempt to enqueue (non-blocking)
        // We use a request token (simplified: queue position)
        String requestToken = context.userId() + "-" + context.service() + "-" + System.nanoTime();

        boolean enqueued = queueState.queue.offer(requestToken);

        if (enqueued) {
            // Step 3: Success - request accepted for processing
            long currentDepth = queueState.queue.size();
            log.info(
                    "Request enqueued for {}. Queue depth: {}",
                    key, currentDepth
            );
            return RateLimitDecision.allowed(
                    currentDepth,
                    "request_queued:" + requestToken
            );
        } else {
            // Step 4: Queue full - rejection
            log.warn(
                    "Queue full for {}. Rejecting request. Queue capacity: {}",
                    key, config.capacity()
            );
            // Retry after one leak interval
            long retryAfterMs = config.getRefillIntervalMs();
            return RateLimitDecision.rejected(
                    retryAfterMs,
                    "queue_full"
            );
        }
    }

    @Override
    public String getAlgorithmName() {
        return "LEAKY_BUCKET";
    }

    /**
     * Reset all queues (useful for testing).
     * In Phase 2, this would clear Redis ZSET.
     */
    @Override
    public void reset() {
        log.info("Resetting all Leaky Bucket state");
        queues.clear();
    }

    /**
     * Debug method: get queue state (for testing).
     */
    protected QueueState getQueueState(String key) {
        return queues.get(key);
    }

    /**
     * Debug method: get current queue size.
     */
    public long getQueueDepth(String key) {
        QueueState state = queues.get(key);
        return state != null ? state.queue.size() : 0;
    }
}