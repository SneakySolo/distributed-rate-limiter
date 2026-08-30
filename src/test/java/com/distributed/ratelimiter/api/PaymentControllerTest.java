package com.distributed.ratelimiter.api;

import com.distributed.ratelimiter.TestRedisContainer;
import com.distributed.ratelimiter.service.PaymentStatusService;
import com.distributed.ratelimiter.domain.RequestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
public class PaymentControllerTest extends TestRedisContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private PaymentStatusService paymentStatusService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanup() {
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    public void testAcceptsPaymentWithinLimit() throws Exception {
        MvcResult result = mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", "user1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("requestId"));
    }

    @Test
    public void testReturns429WhenQueueFull() throws Exception {
        String userId = "user-queue";

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/payment/process")
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    public void testStatusEndpointReturnsQueuedStatus() throws Exception {
        MvcResult result = mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", "user-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        ApiModels.PaymentProcessResponse processResponse =
                objectMapper.readValue(response, ApiModels.PaymentProcessResponse.class);

        String requestId = processResponse.requestId();

        mockMvc.perform(get("/payment/status/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    public void testStatusEndpointReturns404ForUnknownRequest() throws Exception {
        mockMvc.perform(get("/payment/status/unknown-request-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testIndependentUserQueues() throws Exception {
        String user1 = "payment-user1";
        String user2 = "payment-user2";

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/payment/process")
                            .header("X-User-Id", user1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", user1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", user2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    public void testMissingUserIdHeader() throws Exception {
        mockMvc.perform(post("/payment/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testRequestIdUniqueness() throws Exception {
        String userId = "unique-test";

        MvcResult result1 = mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andReturn();

        MvcResult result2 = mockMvc.perform(post("/payment/process")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"100\", \"currency\": \"USD\"}"))
                .andReturn();

        ApiModels.PaymentProcessResponse response1 =
                objectMapper.readValue(result1.getResponse().getContentAsString(), ApiModels.PaymentProcessResponse.class);
        ApiModels.PaymentProcessResponse response2 =
                objectMapper.readValue(result2.getResponse().getContentAsString(), ApiModels.PaymentProcessResponse.class);

        assertNotEquals(response1.requestId(), response2.requestId());
    }
}
