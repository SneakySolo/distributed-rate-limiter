package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.config.RateLimiterConfig;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);
    private static final String SCRIPT_SOURCE = """
        local bucketKey = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local refillRatePerMin = tonumber(ARGV[2])
        local ttlSecs = tonumber(ARGV[3])
        local nowMs = tonumber(ARGV[4])
 
        local bucket = redis.call('HGETALL', bucketKey)
        local tokens = capacity
        local lastRefillMs = nowMs
 
        if #bucket > 0 then
            tokens = tonumber(bucket[2]) or capacity
            lastRefillMs = tonumber(bucket[4]) or nowMs
        end
 
        local elapsedMs = nowMs - lastRefillMs
        local refillIntervalMs = 60000 / refillRatePerMin
 
        if elapsedMs >= refillIntervalMs then
            local numRefills = math.floor(elapsedMs / refillIntervalMs)
            tokens = math.min(tokens + numRefills, capacity)
            lastRefillMs = lastRefillMs + (numRefills * refillIntervalMs)
        end
 
        local allowed = 0
        local remaining = tokens
        local retryAfterMs = 0
 
        if tokens > 0 then
            allowed = 1
            tokens = tokens - 1
            remaining = tokens
        else
            retryAfterMs = math.ceil(refillIntervalMs)
        end
 
        redis.call('HSET', bucketKey, 'tokens', remaining, 'lastRefillMs', lastRefillMs)
        redis.call('EXPIRE', bucketKey, ttlSecs)
 
        return {allowed, remaining, retryAfterMs}
        """;

    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimiterConfig config;
    private final RedisScript<List> script;

    public TokenBucketRateLimiter(RedisTemplate<String, String> redisTemplate, RateLimiterConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        this.script = RedisScript.of(SCRIPT_SOURCE, List.class);
    }

    @Override
    public RateLimitDecision tryConsume(String userId, String service) {
        try {
            String bucketKey = formatKey(userId, service);
            long nowMs = fetchRedisTimeMs();

            List<Object> result = redisTemplate.execute(
                    script,
                    Arrays.asList(bucketKey),
                    String.valueOf(config.getTokenBucket().getCapacity()),
                    String.valueOf(config.getTokenBucket().getRefillRatePerMinute()),
                    String.valueOf(config.getTokenBucket().getTtlSeconds()),
                    String.valueOf(nowMs)
            );

            if (result != null && result.size() >= 3) {
                long allowed = ((Number) result.get(0)).longValue();
                long remaining = ((Number) result.get(1)).longValue();
                long retryAfterMs = ((Number) result.get(2)).longValue();

                if (allowed == 1) {
                    return RateLimitDecision.allowed(remaining);
                } else {
                    return RateLimitDecision.rejected(retryAfterMs);
                }
            }

            return RateLimitDecision.unavailable("Script execution failed");
        } catch (Exception e) {
            log.error("Redis unavailable for token bucket", e);
            return RateLimitDecision.unavailable("Redis unavailable: " + e.getMessage());
        }
    }

    private String formatKey(String userId, String service) {
        return "tb:" + userId + ":" + service;
    }

    private long fetchRedisTimeMs() {
        return System.currentTimeMillis();
    }
}