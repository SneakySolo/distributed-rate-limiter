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

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimiterConfig config;
    private final RedisScript<List> script;

    public TokenBucketRateLimiter(
            RedisTemplate<String, String> redisTemplate,
            RateLimiterConfig config,
            LuaScriptLoader scriptLoader) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        this.script = RedisScript.of(scriptLoader.getTokenBucketScript(), List.class);
    }

    @Override
    public RateLimitDecision tryConsume(String userId, String service) {
        try {
            String bucketKey = formatKey(userId, service);

            List<Object> result = redisTemplate.execute(
                    script,
                    Arrays.asList(bucketKey),
                    String.valueOf(config.getTokenBucket().getCapacity()),
                    String.valueOf(config.getTokenBucket().getRefillRatePerMinute()),
                    String.valueOf(config.getTokenBucket().getTtlSeconds())
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
}