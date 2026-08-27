package com.distributed.ratelimiter.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads Lua scripts into Redis on application startup.
 *
 * DESIGN DECISION: Why pre-load scripts?
 * ═════════════════════════════════════
 * 1. Redis caches scripts by SHA-1
 * 2. EVALSHA is faster than EVAL (script already in Redis)
 * 3. Centralized script management (version control)
 * 4. Ensures all instances use same script
 *
 * WHEN SCRIPTS ARE LOADED:
 * - On ApplicationReadyEvent (after Spring fully initializes)
 * - At startup, not on every request
 * - Only once per application instance
 *
 * SCRIPT AVAILABILITY:
 * - All instances load scripts independently
 * - Redis caches scripts separately (idempotent)
 * - Multiple instances loading same script = no conflict
 *
 * PHASE 2: Load token_bucket_consume.lua
 * PHASE 3: Add leaky_bucket_dequeue.lua (when worker added)
 */
@Slf4j
@Component
public class LuaScriptLoader {

    private final RedisTemplate<String, Object> redisTemplate;

    // Stores SHA-1 hash of each script (returned by Redis on load)
    private String tokenBucketSha;

    public LuaScriptLoader(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Load all Lua scripts on application startup.
     *
     * CALLED BY: Spring via @EventListener
     * WHEN: After ApplicationContext is fully initialized
     * RUNS: Once per application instance startup
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadScripts() {
        log.info("════════════════════════════════════════════════════════════");
        log.info("Loading Lua scripts into Redis...");
        log.info("════════════════════════════════════════════════════════════");

        try {
            loadTokenBucketScript();

            log.info("════════════════════════════════════════════════════════════");
            log.info("✓ All Lua scripts loaded successfully");
            log.info("  Token Bucket SHA: {}", tokenBucketSha);
            log.info("════════════════════════════════════════════════════════════");
        } catch (Exception e) {
            log.error("✗ Failed to load Lua scripts", e);
            throw new RuntimeException("Lua script loading failed", e);
        }
    }

    /**
     * Load Token Bucket Lua script.
     *
     * SCRIPT: token_bucket_consume.lua
     * PURPOSE: Atomic refill + token consumption
     *
     * WHAT REDIS RETURNS:
     * - SHA-1 hash of script (40 character hex string)
     * - Used later with EVALSHA instead of EVAL
     * - Example: "5e6c9d5c5d0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c"
     *
     * BENEFITS OF SHA-1 CACHING:
     * ✓ Script stored in Redis memory once
     * ✓ EVALSHA executes same script without re-transmitting
     * ✓ Bandwidth optimization (important for large scripts)
     * ✓ Faster execution (parse once, reuse)
     */
    private void loadTokenBucketScript() {
        log.info("Loading Token Bucket script...");

        try {
            String scriptContent = readLuaScript("token_bucket_consume.lua");

            // SCRIPT LOAD returns SHA-1 hash
            this.tokenBucketSha = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .scriptLoad(scriptContent.getBytes());

            log.info("✓ Token Bucket script loaded");
            log.info("  SHA-1: {}", tokenBucketSha);
            log.info("  Location: resources/lua/token_bucket_consume.lua");
        } catch (Exception e) {
            log.error("✗ Failed to load Token Bucket script", e);
            throw new RuntimeException("Token Bucket script loading failed", e);
        }
    }

    /**
     * Read Lua script from classpath.
     *
     * LOCATION: src/main/resources/lua/{scriptName}
     *
     * PATH RESOLUTION:
     * - getClass().getResourceAsStream() reads from classpath
     * - After Maven build, resources/ → target/classes/
     * - Spring Boot packages it in JAR at root
     *
     * EXAMPLE:
     * - File path: src/main/resources/lua/token_bucket_consume.lua
     * - Classpath path: /lua/token_bucket_consume.lua
     * - Access via: getResourceAsStream("/lua/token_bucket_consume.lua")
     */
    private String readLuaScript(String scriptName) {
        try {
            byte[] bytes = getClass()
                    .getResourceAsStream("/lua/" + scriptName)
                    .readAllBytes();
            return new String(bytes);
        } catch (Exception e) {
            log.error("✗ Failed to read Lua script: {}", scriptName, e);
            throw new RuntimeException(
                    "Failed to load Lua script: " + scriptName +
                            " (path: resources/lua/" + scriptName + ")",
                    e
            );
        }
    }

    // ========================================================================
    // PUBLIC ACCESSORS: Return SHA-1 for EVALSHA execution
    // ========================================================================

    /**
     * Get SHA-1 of Token Bucket script.
     *
     * USAGE: In TokenBucketRateLimiter.checkRateLimit()
     * redisTemplate.execute(
     *   new DefaultRedisScript<>(..., List.class),
     *   keys,
     *   args
     * );
     *
     * Or manually:
     * redisTemplate.execute("EVALSHA", getTokenBucketSha(), ...)
     */
    public String getTokenBucketSha() {
        if (tokenBucketSha == null) {
            throw new RuntimeException(
                    "Token Bucket script not loaded! " +
                            "Check application startup logs for errors."
            );
        }
        return tokenBucketSha;
    }

    /**
     * Check if scripts are loaded (for health checks).
     * USAGE: Health check endpoint, monitoring
     */
    public boolean areScriptsLoaded() {
        return tokenBucketSha != null;
    }
}