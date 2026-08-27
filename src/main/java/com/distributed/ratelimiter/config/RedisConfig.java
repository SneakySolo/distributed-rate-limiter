package com.distributed.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for Phase 2.
 *
 * DESIGN DECISION: Jedis vs Lettuce?
 * - Using Jedis (simpler, chosen in Phase 1)
 * - Spring Boot auto-picks Jedis if available
 *
 * BEANS PROVIDED:
 * - StringRedisTemplate: For simple String keys/values
 * - RedisTemplate: For complex operations (ZSET, Lua scripts)
 *
 * SPRING BOOT AUTO-CONFIG:
 * When Redis starter is added, Spring automatically:
 * 1. Creates RedisConnectionFactory (based on spring.data.redis.*)
 * 2. Connects to Redis on startup
 * 3. These beans use that factory
 */
@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate bean for simple String operations.
     *
     * Used for:
     * - Token bucket state (storing tokens|lastRefillMs)
     * - Payment request status (QUEUED|PROCESSING|COMPLETED)
     *
     * Why separate?
     * - Simpler API for string-only operations
     * - Less serialization overhead
     * - Easier to debug (redis-cli sees plain strings)
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Generic RedisTemplate bean for complex operations.
     *
     * Used for:
     * - Lua script execution (EVALSHA)
     * - Sorted set operations (ZSET for Leaky Bucket queue)
     * - Hash operations (future extensibility)
     *
     * SERIALIZATION:
     * - Keys: StringRedisSerializer (plain text, debuggable)
     * - Values: StringRedisSerializer (JSON when needed)
     * - Hashes: String serialization for both keys and values
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serialization for keys (debuggable in redis-cli)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use String serialization for values (JSON stored as strings)
        template.setValueSerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        // Initialize the template
        template.afterPropertiesSet();
        return template;
    }
}