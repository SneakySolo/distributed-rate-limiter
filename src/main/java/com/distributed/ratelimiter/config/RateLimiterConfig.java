package com.distributed.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/*
 * This is to load the algorithm metric into them from application.yml
 * They get it from appliaction.yml
 * but we also have default values set here in case
 * on successfully getting values, they override the default values
*/

@Component
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterConfig {

    private TokenBucketProperties tokenBucket = new TokenBucketProperties();
    private LeakyBucketProperties leakyBucket = new LeakyBucketProperties();

    public TokenBucketProperties getTokenBucket() {
        return tokenBucket;
    }

    public void setTokenBucket(TokenBucketProperties tokenBucket) {
        this.tokenBucket = tokenBucket;
    }

    public LeakyBucketProperties getLeakyBucket() {
        return leakyBucket;
    }

    public void setLeakyBucket(LeakyBucketProperties leakyBucket) {
        this.leakyBucket = leakyBucket;
    }

    // default values set here, gets override by values from application.yml
    public static class TokenBucketProperties {
        private long capacity = 100;
        private long refillRatePerMinute = 100;
        private long ttlSeconds = 300;

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getRefillRatePerMinute() {
            return refillRatePerMinute;
        }

        public void setRefillRatePerMinute(long refillRatePerMinute) {
            this.refillRatePerMinute = refillRatePerMinute;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    // default values set here, gets override by values from application.yml
    public static class LeakyBucketProperties {
        private long capacity = 100;
        private long leakRatePerMinute = 100;
        private long workerIntervalMillis = 600;

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getLeakRatePerMinute() {
            return leakRatePerMinute;
        }

        public void setLeakRatePerMinute(long leakRatePerMinute) {
            this.leakRatePerMinute = leakRatePerMinute;
        }

        public long getWorkerIntervalMillis() {
            return workerIntervalMillis;
        }

        public void setWorkerIntervalMillis(long workerIntervalMillis) {
            this.workerIntervalMillis = workerIntervalMillis;
        }
    }
}