package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.domain.RateLimitDecision;

public interface RateLimiter {
    RateLimitDecision tryConsume(String userId, String service);
}