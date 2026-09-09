package com.distributed.ratelimiter.integration;

import com.distributed.ratelimiter.TestRedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimiter.tokenBucket.refillRatePerMinute=1"
})
@DisplayName("Distributed Multi-Instance Rate Limiting")
class DistributedMultiInstanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final GenericContainer<?> redis = TestRedisContainer.getContainer();

    @DynamicPropertySource
    static void setRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> redis.getHost());
        registry.add("spring.data.redis.port", () -> redis.getFirstMappedPort());
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    @DisplayName("Distributed correctness: shared Redis state enforces single global rate limit")
    void testSameUserMultipleInstancesSharedState() throws Exception {
        String userId = "test-user-123";
        String otpTbKey = "tb:" + userId + ":otp";

        // In a real Phase 2 deployment with multiple Spring Boot instances (app1, app2, app3)
        // all backed by the same Redis, requests from any instance update shared state.
        //
        // Since this test runs in a single JVM with one MockMvc, we simulate this by:
        // 1. Making all 100 requests via the same MockMvc (they all hit the same app instance)
        // 2. Verifying that Redis state is shared and correctly enforces the limit
        //
        // The key assertion is: after 100 requests, the next request is rate-limited.
        // This proves the rate limiter checks shared Redis state, not in-memory state.

        // Make 100 requests (full capacity of token bucket)
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // Verify Redis state exists (proves shared storage is being used)
        assertTrue(redisTemplate.hasKey(otpTbKey),
                "Rate limiter state should be in Redis for distributed access");

        // Next request after exhaustion should be rate-limited
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", userId)
                        .contentType("application/json")
                        .content("{\"phone\":\"1234567890\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Different users maintain independent rate limit state")
    void testDifferentUsersIndependentState() throws Exception {
        String userId1 = "user-1";
        String userId2 = "user-2";

        // User 1: exhaust their 100-token bucket
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId1)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // User 1 is now rate-limited
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", userId1)
                        .contentType("application/json")
                        .content("{\"phone\":\"1234567890\"}"))
                .andExpect(status().isTooManyRequests());

        // User 2: should still be able to send 100 requests independently
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId2)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // User 2 is now rate-limited
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", userId2)
                        .contentType("application/json")
                        .content("{\"phone\":\"1234567890\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("OTP and Payment endpoints maintain independent rate limit per user")
    void testOtpAndPaymentIndependentLimits() throws Exception {
        String userId = "test-user-456";

        // Exhaust OTP limit (100 requests)
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // OTP should be exhausted
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", userId)
                        .contentType("application/json")
                        .content("{\"phone\":\"1234567890\"}"))
                .andExpect(status().isTooManyRequests());

        // Payment should still have capacity (100 tokens available)
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/payment/process")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"amount\":100.0}"))
                    .andExpect(status().isAccepted());
        }

        // Payment should still have tokens available
        mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", userId)
                        .contentType("application/json")
                        .content("{\"amount\":100.0}"))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("Redis-backed state persists across requests in distributed environment")
    void testRedisStatePersistence() throws Exception {
        String userId = "persistence-test-user";
        String otpTbKey = "tb:" + userId + ":otp";

        // Make 25 requests
        for (int i = 0; i < 25; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // Verify state exists in Redis
        assertTrue(redisTemplate.hasKey(otpTbKey), "...");

        // Simulate a second instance reading the same state
        // Make 25 more requests
        for (int i = 0; i < 25; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // After 50 total, should still have capacity for 50 more
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // Next should be rate-limited
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", userId)
                        .contentType("application/json")
                        .content("{\"phone\":\"1234567890\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Real distributed test: concurrent requests from same user exhaust single shared bucket")
    void testConcurrentRequestsAcrossDistributedInstances() throws Exception {
        String userId = "concurrent-user-789";
        String otpTbKey = "tb:" + userId + ":otp";

        // This test simulates what happens in Phase 2 with multiple instances:
        // All instances read/write the same Redis state. If not atomic, race conditions
        // could allow more than 100 tokens to be consumed. This test catches that.
        //
        // We use concurrent requests in this single instance to simulate
        // concurrent load across multiple instances hitting the same Redis.

        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(100);
        java.util.concurrent.atomic.AtomicInteger successCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger rateLimitedCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        // Submit 100 concurrent requests
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    var result = mockMvc.perform(post("/otp/send")
                                    .header("X-User-Id", userId)
                                    .contentType("application/json")
                                    .content("{\"phone\":\"1234567890\"}"))
                            .andReturn();

                    if (result.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                    } else if (result.getResponse().getStatus() == 429) {
                        rateLimitedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all to complete
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Verify exactly 100 succeeded and 0 were rate-limited
        // (all 100 requests are allowed; the 101st would be limited)
        assertTrue(successCount.get() == 100 && rateLimitedCount.get() == 0,
                "With atomic Lua script, exactly 100 concurrent requests should succeed. " +
                        "Got " + successCount.get() + " success, " + rateLimitedCount.get() + " rate-limited. " +
                        "This proves Lua atomicity is working in distributed scenario.");

        // Verify Redis state exists
        assertTrue(redisTemplate.hasKey(otpTbKey),
                "Rate limiter state should be in Redis");

        // Now the 101st request should fail
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", userId)
                        .contentType("application/json")
                        .content("{\"phone\":\"1234567890\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }
}