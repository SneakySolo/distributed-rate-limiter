package com.distributed.ratelimiter.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;

/**
 * RedisClient: Abstraction layer over Jedis for rate limiting operations.
 *
 * PURPOSE:
 * ========
 * - Simplify interaction with Redis for rate limiting
 * - Centralize error handling and logging
 * - Hide Jedis details from rate limiter algorithms
 * - Manage connection lifecycle (get from pool, return safely)
 *
 * DESIGN:
 * -------
 * Why an abstraction layer?
 * 1. Rate limiters don't need to know about JedisPool
 * 2. Easier to mock for unit tests
 * 3. Single place to add retries/fallback logic
 * 4. Future: could swap Jedis for another Redis client
 *
 * PATTERN: Try-with-resources
 * -----------------------------
 * try (Jedis jedis = pool.getResource()) {
 *     // Use jedis
 * }  // Connection automatically returned to pool
 *
 * This ensures connections are never leaked.
 */
@Slf4j
@Component
public class RedisClient {

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public RedisClient(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Execute a Lua script with the given keys and arguments.
     *
     * WHY THIS METHOD?
     * - Encapsulates EVAL command
     * - Handles script as string (Phase 2: inline scripts)
     * - Manages Jedis connection lifecycle
     * - Provides consistent error handling
     *
     * PARAMETERS:
     * - script: Lua script as string (multiline OK)
     * - numKeys: How many args are KEYS[] vs ARGV[]
     *            KEYS[] = first numKeys args
     *            ARGV[] = remaining args
     *
     * EXAMPLE:
     * evalScript(
     *   "return redis.call('GET', KEYS[1])",  -- script
     *   1,                                     -- numKeys = 1
     *   "mykey"                               -- KEYS[1] = "mykey"
     * );
     *
     * RETURN VALUE:
     * - Object: Could be String, Long, List, etc.
     * - Redis returns different types; we return as-is for caller to parse
     *
     * ERROR HANDLING:
     * - JedisConnectionException: Redis unreachable → log + rethrow
     * - Script error: Lua error message → log + rethrow
     *
     * FAIL-CLOSED PRINCIPLE:
     * We don't suppress errors. Better to reject request than hide Redis failure.
     */
    public Object evalScript(String script, int numKeys, String... args) {
        try (Jedis jedis = jedisPool.getResource()) {
            log.debug(
                    "Executing Lua script: numKeys={}, args={}",
                    numKeys,
                    Arrays.stream(args).limit(3).toList() // Log first 3 args
            );
            return jedis.eval(script, numKeys, args);
        } catch (Exception e) {
            log.error("Redis Lua script execution failed: {}", e.getMessage(), e);
            throw new RedisOperationException("Script execution failed", e);
        }
    }

    /**
     * Get a string value from Redis.
     *
     * USAGE:
     * - Retrieve bucket state (JSON)
     * - Retrieve request status (status string)
     * - Check existence of keys
     *
     * RETURN:
     * - String if key exists
     * - null if key doesn't exist
     */
    public String get(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(key);
            log.debug("Redis GET {}: {}", key, value != null ? "found" : "not found");
            return value;
        } catch (Exception e) {
            log.error("Redis GET failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("GET operation failed", e);
        }
    }

    /**
     * Set a string value in Redis with optional TTL.
     *
     * USAGE:
     * - Store bucket state
     * - Store request status
     *
     * PARAMETERS:
     * - key: Redis key
     * - value: String value
     * - ttlMs: Optional TTL in milliseconds (null = no expiry)
     *
     * IMPLEMENTATION:
     * - If ttlMs is null: SET key value
     * - If ttlMs > 0: SET key value EX (seconds)
     *
     * TTL RATIONALE:
     * - Prevents stale rate limit data in Redis
     * - Unused rate limits auto-expire after inactivity
     * - Saves Redis memory (no manual cleanup needed)
     */
    public void set(String key, String value, Long ttlMs) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (ttlMs != null && ttlMs > 0) {
                // SET with expiry
                long seconds = (ttlMs + 999) / 1000; // Round up ms → seconds
                jedis.setex(key, seconds, value);
                log.debug("Redis SET {} (TTL: {}s)", key, seconds);
            } else {
                // SET without expiry
                jedis.set(key, value);
                log.debug("Redis SET {}", key);
            }
        } catch (Exception e) {
            log.error("Redis SET failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("SET operation failed", e);
        }
    }

    /**
     * Delete a key from Redis.
     *
     * USAGE:
     * - Clean up expired rate limits (for testing)
     * - Remove completed requests
     *
     * RETURN:
     * - 1 if key was deleted
     * - 0 if key didn't exist
     */
    public long delete(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            long deleted = jedis.del(key);
            log.debug("Redis DEL {}: {}", key, deleted > 0 ? "deleted" : "not found");
            return deleted;
        } catch (Exception e) {
            log.error("Redis DEL failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("DEL operation failed", e);
        }
    }

    /**
     * Check if a key exists in Redis.
     *
     * USAGE:
     * - Verify state exists before reading
     * - Check if rate limit has been initialized
     *
     * RETURN:
     * - true if key exists
     * - false if key doesn't exist
     */
    public boolean exists(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            boolean exists = jedis.exists(key);
            log.debug("Redis EXISTS {}: {}", key, exists ? "yes" : "no");
            return exists;
        } catch (Exception e) {
            log.error("Redis EXISTS failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("EXISTS operation failed", e);
        }
    }

    /**
     * Get a JSON value and deserialize to Java object.
     *
     * USAGE:
     * - Retrieve bucket state from Redis
     * - Parse into BucketState record
     *
     * DESIGN:
     * - Get JSON string from Redis
     * - Parse with Jackson ObjectMapper
     * - Handle null/missing values gracefully
     *
     * RETURN:
     * - T (parsed object) if found
     * - null if key doesn't exist
     * - RuntimeException if JSON parse fails
     *
     * TYPE SAFETY:
     * - Class<T> parameter allows type-safe deserialization
     * - Caller specifies expected type at call site
     */
    public <T> T getJson(String key, Class<T> type) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(key);
            if (json == null) {
                log.debug("Redis GET {} (JSON): not found", key);
                return null;
            }
            T value = objectMapper.readValue(json, type);
            log.debug("Redis GET {} (JSON): deserialized to {}", key, type.getSimpleName());
            return value;
        } catch (JsonProcessingException e) {
            log.error("JSON deserialization failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("JSON parse failed", e);
        } catch (Exception e) {
            log.error("Redis GET (JSON) failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("GET operation failed", e);
        }
    }

    /**
     * Set a Java object as JSON in Redis with optional TTL.
     *
     * USAGE:
     * - Store bucket state as JSON
     * - Serialize Java object to Redis
     *
     * DESIGN:
     * - Serialize object to JSON with Jackson
     * - Store as Redis string (atomic)
     * - Apply TTL if specified
     *
     * ERROR HANDLING:
     * - Serialization fails: RuntimeException
     * - Redis error: RuntimeException
     *
     * FAIL-CLOSED:
     * Any error is fatal. Better than storing corrupt state.
     */
    public <T> void setJson(String key, T value, Long ttlMs) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(value);
            if (ttlMs != null && ttlMs > 0) {
                long seconds = (ttlMs + 999) / 1000;
                jedis.setex(key, seconds, json);
                log.debug("Redis SET {} (JSON, TTL: {}s)", key, seconds);
            } else {
                jedis.set(key, json);
                log.debug("Redis SET {} (JSON)", key);
            }
        } catch (JsonProcessingException e) {
            log.error("JSON serialization failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("JSON serialization failed", e);
        } catch (Exception e) {
            log.error("Redis SET (JSON) failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("SET operation failed", e);
        }
    }

    /**
     * Add member to sorted set (ZSET) with score.
     *
     * USAGE:
     * - Add request to Leaky Bucket queue
     * - Score = enqueue timestamp (orders by time)
     * - Member = requestId (unique identifier)
     *
     * PARAMETERS:
     * - key: ZSET key
     * - score: Double (timestamp in milliseconds as double)
     * - member: String (requestId)
     *
     * RETURN:
     * - 1 if member added (new)
     * - 0 if member updated (already existed)
     */
    public long zadd(String key, double score, String member) {
        try (Jedis jedis = jedisPool.getResource()) {
            long added = jedis.zadd(key, score, member);
            log.debug("Redis ZADD {} member={}, score={}: {}",
                    key, member, score, added > 0 ? "added" : "updated");
            return added;
        } catch (Exception e) {
            log.error("Redis ZADD failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("ZADD operation failed", e);
        }
    }

    /**
     * Get size (cardinality) of sorted set.
     *
     * USAGE:
     * - Check queue depth for Leaky Bucket
     * - Determine if queue is full (size >= capacity)
     *
     * RETURN:
     * - Long: number of members in ZSET
     */
    public long zcard(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            long size = jedis.zcard(key);
            log.debug("Redis ZCARD {}: {}", key, size);
            return size;
        } catch (Exception e) {
            log.error("Redis ZCARD failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("ZCARD operation failed", e);
        }
    }

    /**
     * Get range of sorted set members (oldest to newest).
     *
     * USAGE:
     * - Retrieve oldest requests from Leaky Bucket queue
     * - Processed by background worker
     *
     * PARAMETERS:
     * - key: ZSET key
     * - start: 0 = first member (lowest score)
     * - end: count-1 = last member
     *
     * EXAMPLE:
     * zrange("rl:leaky-bucket:queue:user1:payment", 0, 9)
     *   Returns: 10 oldest request IDs
     *
     * RETURN:
     * - Set of members in range (ordered by score)
     */
    public List<String> zrange(String key, long start, long end) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> members = jedis.zrange(key, start, end);  // ← NOW CORRECT
            log.debug("Redis ZRANGE {} {}..{}: {} members", key, start, end, members.size());
            return members;
        } catch (Exception e) {
            log.error("Redis ZRANGE failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("ZRANGE operation failed", e);
        }
    }

    /**
     * Remove member from sorted set.
     *
     * USAGE:
     * - Remove processed requests from queue
     * - Clean up completed tasks
     *
     * RETURN:
     * - 1 if member was removed
     * - 0 if member didn't exist
     */
    public long zrem(String key, String... members) {
        try (Jedis jedis = jedisPool.getResource()) {
            long removed = jedis.zrem(key, members);
            log.debug("Redis ZREM {}: {} members removed", key, removed);
            return removed;
        } catch (Exception e) {
            log.error("Redis ZREM failed for key {}: {}", key, e.getMessage(), e);
            throw new RedisOperationException("ZREM operation failed", e);
        }
    }

    /**
     * Custom exception for Redis operations.
     */
    public static class RedisOperationException extends RuntimeException {
        public RedisOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}