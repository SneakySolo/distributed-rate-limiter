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
    @DisplayName("Same user across multiple instances shares one global rate limit state")
    void testSameUserMultipleInstancesSharedState() throws Exception {
        String userId = "test-user-123";
        String otpTbKey = "tb:" + userId + ":otp";

        // Simulate 50 requests from instance-1 (via same MockMvc, represents app1)
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // At this point, instance-1 has consumed 50 tokens
        // In a real Phase 2 deployment, instance-2 and instance-3 would see this state
        // We simulate instance-2's view by checking Redis directly
        assertTrue(redisTemplate.hasKey(otpTbKey), "...");

        // Simulate 50 more requests from instance-2 (checking Redis state)
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType("application/json")
                            .content("{\"phone\":\"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // After 100 total requests, bucket should be exhausted
        // Next request should get 429 Too Many Requests
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
}