package com.distributed.ratelimiter.api;

import com.distributed.ratelimiter.TestRedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
public class OtpControllerTest extends TestRedisContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void cleanup() {
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    public void testAllowsOtpWithinLimit() throws Exception {
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", "user1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    public void testReturns429WhenLimitExceeded() throws Exception {
        String userId = "user-limit";

        // Send exactly 100 requests (capacity limit)
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phoneNumber\": \"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // The 101st request should be rejected with 429
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"1234567890\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    public void testIndependentUserLimits() throws Exception {
        String user1 = "otp-user1";
        String user2 = "otp-user2";

        // Exhaust user1's quota
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/otp/send")
                            .header("X-User-Id", user1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phoneNumber\": \"1234567890\"}"))
                    .andExpect(status().isOk());
        }

        // User1 should be rate limited
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", user1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"1234567890\"}"))
                .andExpect(status().isTooManyRequests());

        // User2 should still be allowed (independent rate limit)
        mockMvc.perform(post("/otp/send")
                        .header("X-User-Id", user2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"1234567890\"}"))
                .andExpect(status().isOk());
    }

    @Test
    public void testMissingUserIdHeader() throws Exception {
        mockMvc.perform(post("/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"1234567890\"}"))
                .andExpect(status().isBadRequest());
    }
}