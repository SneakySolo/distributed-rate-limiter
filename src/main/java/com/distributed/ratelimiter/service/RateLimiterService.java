package com.distributed.ratelimiter.service;

import com.distributed.ratelimiter.algorithm.LeakyBucketRateLimiter;
import com.distributed.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.distributed.ratelimiter.domain.RateLimitDecision;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterService {

    private final TokenBucketRateLimiter tokenBucket;
    private final LeakyBucketRateLimiter leakyBucket;

    public RateLimiterService(TokenBucketRateLimiter tokenBucket, LeakyBucketRateLimiter leakyBucket) {
        this.tokenBucket = tokenBucket;
        this.leakyBucket = leakyBucket;
    }

    public RateLimitDecision checkOtp(String userId) {
        return tokenBucket.tryConsume(userId, "otp");
    }

    public RateLimitDecision checkPayment(String userId) {
        return leakyBucket.tryConsume(userId, "payment");
    }
}
