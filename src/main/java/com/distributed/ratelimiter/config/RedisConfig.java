package com.distributed.ratelimiter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.*;

import java.util.Objects;

/**
 * Redis Configuration for Phase 2.
 *
 * PURPOSE:
 * ========
 * Centralized configuration for Redis connection pooling and client initialization.
 * Allows rate limiting state to move from in-memory HashMap to shared Redis.
 *
 * DESIGN:
 * - JedisPool: Thread-safe connection pool
 * - Configurable via application.yml properties
 * - Fail-closed on connection errors (better than allowing too much traffic)
 *
 * WHY POOLING?
 * ============
 * Each HTTP request needs a Redis connection. Without pooling:
 *   - Creating new connection per request = slow (TCP handshake + auth)
 *   - Connections leak, exhaust OS file descriptors
 *
 * With JedisPool:
 *   - Maintains 8-32 pre-opened connections
 *   - Reuses connections across requests
 *   - Validates connections on borrow
 *   - Closes idle connections after timeout
 *
 * CONFIGURATION:
 * ==============
 * Read from application.yml:
 *   redis:
 *     host: localhost
 *     port: 6379
 *     password: (optional)
 *     timeout: 5000
 *     max-pool-size: 32
 *     min-idle: 8
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * Properties class for Redis configuration.
     * Binds to 'redis.*' properties in application.yml
     */
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "redis")
    public static class RedisProperties {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int timeout = 5000;           // ms, socket timeout
        private int maxPoolSize = 32;         // max active connections
        private int minIdle = 8;              // min idle connections
        private int maxIdle = 16;             // max idle connections
        private long maxWaitMillis = 10000;   // max time to wait for a connection

        // Getters and setters
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }

        public int getMaxIdle() { return maxIdle; }
        public void setMaxIdle(int maxIdle) { this.maxIdle = maxIdle; }

        public long getMaxWaitMillis() { return maxWaitMillis; }
        public void setMaxWaitMillis(long maxWaitMillis) { this.maxWaitMillis = maxWaitMillis; }
    }

    /**
     * Create and configure JedisPool bean.
     *
     * STEP-BY-STEP:
     * 1. Read Redis properties from application.yml
     * 2. Create JedisPoolConfig with optimized settings
     * 3. Instantiate JedisPool (manages connection lifecycle)
     * 4. Test connection on startup
     * 5. Log configuration
     *
     * JEDISPOOLCONFIG SETTINGS:
     * - TestOnBorrow: Validate connection before use (catches stale connections)
     * - TestOnReturn: Validate after use (clean return to pool)
     * - MinEvictableIdleTimeMillis: Connections idle > 5min are evicted
     * - TimeBetweenEvictionRunsMillis: Check for idle connections every 60s
     *
     * These settings ensure pool health without being too aggressive
     * (testing every connection would slow things down).
     */
    @Bean
    public JedisPool jedisPool(RedisProperties props) {
        log.info(
                "Initializing JedisPool: {}:{} (timeout={}ms, pool-size={}-{}, maxWait={}ms)",
                props.getHost(),
                props.getPort(),
                props.getTimeout(),
                props.getMinIdle(),
                props.getMaxPoolSize(),
                props.getMaxWaitMillis()
        );

        JedisPoolConfig config = new JedisPoolConfig();
        config.setJmxEnabled(false);

        // Pool size settings
        config.setMaxTotal(props.getMaxPoolSize());
        config.setMaxIdle(props.getMaxIdle());
        config.setMinIdle(props.getMinIdle());
        config.setMaxWaitMillis(props.getMaxWaitMillis());

        // Connection validation
        config.setTestOnBorrow(true);      // PING connection before use
        config.setTestOnReturn(false);     // Don't PING on return (slower)
        config.setTestWhileIdle(true);     // PING idle connections periodically

        // Eviction policy (clean up stale connections)
        config.setMinEvictableIdleTimeMillis(5 * 60_000);    // 5 minutes
        config.setTimeBetweenEvictionRunsMillis(60_000);     // Check every 60 seconds
        config.setNumTestsPerEvictionRun(3);                 // Test up to 3 connections per run

        // Blocking when pool exhausted (wait for available connection)
        config.setBlockWhenExhausted(true);

        // Create pool
        JedisPool pool;
        if (Objects.nonNull(props.getPassword()) && !props.getPassword().isEmpty()) {
            // With authentication
            pool = new JedisPool(config, props.getHost(), props.getPort(), props.getTimeout(), props.getPassword());
        } else {
            // Without authentication
            pool = new JedisPool(config, props.getHost(), props.getPort(), props.getTimeout());
        }

        // Test connection on startup (fail fast if Redis unavailable)
        try (Jedis jedis = pool.getResource()) {
            String pongReply = jedis.ping();
            if ("PONG".equals(pongReply)) {
                log.info("✓ Redis connection successful");
            } else {
                log.warn("! Redis responded with unexpected PONG: {}", pongReply);
            }
        } catch (Exception e) {
            log.error("✗ Failed to connect to Redis at {}:{}", props.getHost(), props.getPort(), e);
            throw new RuntimeException("Redis connection failed", e);
        }

        return pool;
    }

    /**
     * Register RedisProperties for property binding.
     */
    @Bean
    public RedisProperties redisProperties() {
        return new RedisProperties();
    }
}