package com.distributed.ratelimiter.algorithm;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RateLimit;
import com.distributed.ratelimiter.domain.RequestContext;
import com.distributed.ratelimiter.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token Bucket Rate Limiter - Phase 2: Redis-backed implementation.
 *
 * MIGRATION FROM PHASE 1:
 * =======================
 * Phase 1: State stored in-memory HashMap (not distributed)
 * Phase 2: State stored in Redis (shared across instances)
 *
 * Algorithm logic remains identical:
 * 1. Refill bucket based on elapsed time
 * 2. Consume 1 token if available
 * 3. Return decision (ALLOWED/REJECTED)
 *
 * WHAT CHANGED:
 * - Removed in-memory ConcurrentHashMap
 * - Removed synchronized keyword (Redis handles atomicity)
 * - Inject RedisClient dependency
 * - Call Lua script instead of local refill/consume logic
 * - Pass current timestamp to script (Redis is clock-neutral)
 *
 * WHY LUA SCRIPT:
 * ===============
 * Without Lua, three instances checking same user rate limit concurrently:
 *
 *   Instance 1            Instance 2            Instance 3
 *   READ tokens=100       (waits)               (waits)
 *        ↓
 *   COMPUTE refill=1
 *   WRITE tokens=99  --→  READ tokens=99       READ tokens=99
 *        ↓                COMPUTE refill=1      COMPUTE refill=1
 *   Consume: 98           WRITE tokens=98       WRITE tokens=98
 *
 *   Result: All three allowed same tokens! (race condition)
 *
 * With Lua script (atomic on Redis):
 *   Instance 1 script runs entirely:   READ → COMPUTE → CONSUME → WRITE
 *   Instance 2 waits for Instance 1
 *   Instance 2 script runs entirely:   READ → COMPUTE → CONSUME → WRITE
 *   Instance 3 waits for Instance 2
 *   ...
 *
 *   Result: Serialized execution, no race conditions, correct limit enforced
 *
 * REDIS KEY STRUCTURE:
 * ====================
 * Key: "rl:token-bucket:{userId}:{service}"
 * Example: "rl:token-bucket:user123:otp"
 *
 * Value: JSON string
 * {
 *   "tokens": 95,
 *   "lastRefillTimeMs": 1724893201234
 * }
 *
 * DESIGN DECISION: Why JSON string vs Redis HASH?
 * - JSON as single key = atomic read/write in Lua (no multi-field transactions)
 * - HASH requires HMGET/HMSET = more complex Lua scripting
 * - JSON is human-readable for debugging with redis-cli
 * - Sufficient performance (string operations are O(1) in Redis)
 */
@Slf4j
public class TokenBucketRateLimiter implements RateLimiter {

    private final RateLimit config;
    private final RedisClient redisClient;

    /**
     * Lua script for atomic token refill + consumption.
     * Embedded as multi-line string for readability.
     * (Phase 2: inline scripts; Phase 4 can use SCRIPT LOAD for caching)
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
            local stateJson = redis.call('GET', KEYS[1])
            
            local capacity = tonumber(ARGV[1])
            local refillRatePerMinute = tonumber(ARGV[2])
            local nowMs = tonumber(ARGV[3])
            
            local currentTokens
            local lastRefillTimeMs
            
            if stateJson == false then
                currentTokens = capacity
                lastRefillTimeMs = nowMs
            else
                local tokensMatch = stateJson:match('"tokens":(%d+)')
                currentTokens = tonumber(tokensMatch) or capacity
                
                local lastTimeMatch = stateJson:match('"lastRefillTimeMs":(%d+)')
                lastRefillTimeMs = tonumber(lastTimeMatch) or nowMs
            end
            
            local elapsedMs = nowMs - lastRefillTimeMs
            local tokensToAdd = 0
            
            if elapsedMs > 0 then
                tokensToAdd = (refillRatePerMinute * elapsedMs) / 60000
            end
            
            currentTokens = math.min(capacity, currentTokens + tokensToAdd)
            
            local retryAfterMs = 0
            local allowed = false
            local reason = ""
            
            if currentTokens > 0 then
                currentTokens = currentTokens - 1
                allowed = true
                reason = "token_available"
            else
                allowed = false
                reason = "no_tokens_available"
                retryAfterMs = math.floor(60000 / refillRatePerMinute)
            end
            
            local updatedStateJson = '{"tokens":' .. tostring(math.floor(currentTokens)) ..
                                    ',"lastRefillTimeMs":' .. tostring(nowMs) .. '}'
            
            redis.call('SET', KEYS[1], updatedStateJson)
            
            local decision = allowed and "allowed" or "rejected"
            return {decision, math.floor(currentTokens), reason, retryAfterMs}
            """;

    /**
     * Constructor: dependency injection for config and Redis client.
     *
     * WHY INJECT:
     * - Makes testing easier (mock RedisClient)
     * - Makes dependencies explicit
     * - Follows Spring best practices
     *
     * @param config Rate limit configuration (capacity, refill rate, TTL)
     * @param redisClient Redis client for Lua script execution
     */
    public TokenBucketRateLimiter(RateLimit config, RedisClient redisClient) {
        this.config = config;
        this.redisClient = redisClient;
        log.info("Initialized TokenBucketRateLimiter (Redis-backed, Phase 2)");
    }

    /**
     * Main rate limit check: execute Lua script on Redis.
     *
     * FLOW:
     * -----
     * 1. Extract userId and service from RequestContext
     * 2. Build Redis key for this rate limit
     * 3. Get current timestamp from context
     * 4. Execute Lua script with key and arguments
     * 5. Parse script response (List of values)
     * 6. Convert to RateLimitDecision
     * 7. Log the result
     *
     * PARAMETERS:
     * -----------
     * context: RequestContext containing userId, service, timestamp
     *
     * RETURN:
     * -------
     * RateLimitDecision: allowed/rejected with metadata
     *
     * REDIS INTERACTION:
     * ------------------
     * Key: "rl:token-bucket:{userId}:{service}"
     * Script: TOKEN_BUCKET_SCRIPT (defined above)
     * Args: [capacity, refillRatePerMinute, currentTimeMs]
     *
     * ATOMICITY:
     * ----------
     * Entire script (read → compute → write) happens in one Redis operation.
     * No race conditions, even with multiple instances.
     *
     * ERROR HANDLING:
     * ---------------
     * If Redis unavailable:
     * - RedisClient throws RedisOperationException
     * - Exception propagates up (fail-closed)
     * - Request rejected (safer than allowing unlimited)
     *
     * If script has syntax error:
     * - Redis returns error
     * - Exception logged and propagated
     * - Request rejected
     *
     * PERFORMANCE:
     * -----------
     * Redis EVAL: ~1-5ms (network + script execution)
     * Compared to Phase 1 in-memory: 0.1μs
     * Trade-off: latency for correctness in distributed system
     */
    @Override
    public RateLimitDecision checkRateLimit(RequestContext context) {
        String key = "rl:token-bucket:" + context.getRateLimitKey();
        long nowMs = context.timestampMs();

        log.debug("Checking Token Bucket rate limit: key={}, timestamp={}", key, nowMs);

        try {
            // Execute Lua script
            Object result = redisClient.evalScript(
                    TOKEN_BUCKET_SCRIPT,
                    1,  // numKeys = 1 (only KEYS[1])
                    key,                                           // KEYS[1]
                    String.valueOf(config.capacity()),             // ARGV[1]
                    String.valueOf(config.refillRatePerMinute()),  // ARGV[2]
                    String.valueOf(nowMs)                          // ARGV[3]
            );

            // Parse response
            if (!(result instanceof List<?> list)) {
                log.error("Unexpected script response type: {}", result.getClass());
                // Fail closed: reject on malformed response
                return RateLimitDecision.rejected(
                        config.getRefillIntervalMs(),
                        "script_error"
                );
            }

            if (list.size() != 4) {
                log.error("Script response has wrong size: {} (expected 4)", list.size());
                return RateLimitDecision.rejected(
                        config.getRefillIntervalMs(),
                        "script_error"
                );
            }

            // Extract response fields
            String decision = (String) list.get(0);     // "allowed" or "rejected"
            long remainingTokens = ((Long) list.get(1)); // tokens after consumption
            String reason = (String) list.get(2);        // reason string
            long retryAfterMs = ((Long) list.get(3));    // retry-after (ms)

            // Convert to RateLimitDecision
            if ("allowed".equals(decision)) {
                log.debug(
                        "Token Bucket: ALLOWED for {}. Remaining tokens: {}",
                        key, remainingTokens
                );
                return RateLimitDecision.allowed(
                        remainingTokens,
                        reason
                );
            } else {
                log.warn(
                        "Token Bucket: REJECTED for {}. Retry after: {}ms",
                        key, retryAfterMs
                );
                return RateLimitDecision.rejected(
                        retryAfterMs,
                        reason
                );
            }

        } catch (RedisClient.RedisOperationException e) {
            log.error("Redis operation failed for key {}: {}", key, e.getMessage());
            // Fail closed: reject request when Redis is unavailable
            return RateLimitDecision.rejected(
                    config.getRefillIntervalMs(),
                    "redis_error"
            );
        } catch (Exception e) {
            log.error("Unexpected error in Token Bucket check for key {}: {}", key, e.getMessage(), e);
            // Fail closed: reject on unexpected error
            return RateLimitDecision.rejected(
                    config.getRefillIntervalMs(),
                    "internal_error"
            );
        }
    }

    @Override
    public String getAlgorithmName() {
        return "TOKEN_BUCKET_REDIS";
    }

    /**
     * Reset all Token Bucket state (for testing).
     *
     * USAGE:
     * ------
     * Before running tests, clear all rate limit data from Redis.
     * Allows fresh test scenarios.
     *
     * IMPLEMENTATION:
     * ---------------
     * In Phase 2: We don't implement this yet (would need KEYS pattern matching)
     * Phase 4: Add Redis SCAN to find all "rl:token-bucket:*" keys and delete
     *
     * WHY IMPORTANT:
     * - Tests are isolated (no state leakage between tests)
     * - Reproduce same conditions consistently
     * - Verify rate limit resets properly
     *
     * TODO Phase 4:
     * Add this implementation:
     *   List<String> keys = redisClient.keysPattern("rl:token-bucket:*");
     *   for (String key : keys) {
     *       redisClient.delete(key);
     *   }
     */
    @Override
    public void reset() {
        log.warn("Token Bucket reset not fully implemented in Phase 2");
        log.warn("Requires Redis SCAN pattern matching (coming in Phase 4)");
        // TODO: Implement in Phase 4 when adding advanced Redis operations
    }
}