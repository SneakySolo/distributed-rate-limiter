package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.config.LuaScriptLoader;
import com.distributed.ratelimiter.config.RateLimiterConfig;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class LeakyBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LeakyBucketRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimiterConfig config;
    private final RedisScript<List> enqueueScript;

    public LeakyBucketRateLimiter(
            RedisTemplate<String, String> redisTemplate,
            RateLimiterConfig config,
            LuaScriptLoader scriptLoader) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        this.enqueueScript = RedisScript.of(scriptLoader.getLeakyBucketEnqueueScript(), List.class);
    }

    @Override
    public RateLimitDecision tryConsume(String userId, String service) {
        try {
            String requestId = generateRequestId();
            long nowMs = System.currentTimeMillis();
            String queueKey = formatQueueKey(userId, service);

            // Calculate scheduled time (leak rate = ~1 per 600ms)
            long leakIntervalMs = 60000L / config.getLeakyBucket().getLeakRatePerMinute();
            long queueDepth = getQueueDepth(queueKey);
            long scheduledMs = nowMs + (queueDepth * leakIntervalMs);

            List<Object> result = redisTemplate.execute(
                    enqueueScript,
                    Arrays.asList(queueKey),
                    requestId,
                    String.valueOf(scheduledMs),
                    String.valueOf(config.getLeakyBucket().getCapacity())
            );

            if (result != null && result.size() >= 2) {
                long success = ((Number) result.get(0)).longValue();
                long newDepth = ((Number) result.get(1)).longValue();

                if (success == 1) {
                    String statusKey = formatStatusKey(requestId);
                    redisTemplate.opsForValue().set(statusKey, "QUEUED");
                    redisTemplate.expire(statusKey, java.time.Duration.ofMinutes(10));

                    return RateLimitDecision.allowedAsync(newDepth, requestId);
                } else {
                    return RateLimitDecision.rejected(0);
                }
            }

            return RateLimitDecision.unavailable("Script execution failed");
        } catch (Exception e) {
            log.error("Redis unavailable for leaky bucket", e);
            return RateLimitDecision.unavailable("Redis unavailable: " + e.getMessage());
        }
    }

    private long getQueueDepth(String queueKey) {
        Long depth = redisTemplate.opsForZSet().size(queueKey);
        return depth != null ? depth : 0;
    }

    private String formatQueueKey(String userId, String service) {
        return "lb:" + userId + ":" + service + ":queue";
    }

    private String formatStatusKey(String requestId) {
        return "payment:" + requestId + ":status";
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }
}