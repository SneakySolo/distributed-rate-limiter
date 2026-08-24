package com.distributed.ratelimiter.controller;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.service.RateLimiterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Payment endpoint controller.
 *
 * RESPONSIBILITY:
 * - Handle HTTP requests to /payment/** endpoints
 * - Apply Leaky Bucket rate limiting
 * - Return asynchronous responses (202 Accepted for queued requests)
 * - Provide status polling endpoint
 *
 * DESIGN DECISION: Why different from OTP controller?
 * - OTP uses Token Bucket (immediate response, burst-friendly)
 * - Payment uses Leaky Bucket (queued, asynchronous processing)
 * - Algorithms are fundamentally different, reflected in HTTP behavior
 *
 * ROUTING:
 * /payment/process -> Leaky Bucket (queue and process asynchronously)
 * /payment/status/{requestId} -> Check async processing status
 *
 * RATE LIMIT BEHAVIOR:
 * - 100 requests per minute per user
 * - Queue-based: accepted requests are queued
 * - Leak rate: 1 request every 600ms (fixed)
 * - Queue capacity: 100 requests
 *
 * HTTP RESPONSES:
 * 202 Accepted: Request queued for async processing (includes requestId)
 * 429 Too Many Requests: Queue full (retry later)
 * 503 Service Unavailable: Rate limiter failed (Phase 2+ Redis issues)
 */
@Slf4j
@RestController
@RequestMapping("/payment")
public class PaymentController {

    private final RateLimiterService rateLimiterService;

    public PaymentController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Process payment endpoint (async).
     *
     * Flow:
     * 1. Extract user ID from X-User-Id header
     * 2. Extract payment amount from body
     * 3. Check Leaky Bucket rate limit (enqueue request)
     * 4. If accepted: return 202 with requestId for polling
     * 5. If rejected: return 429 (queue full)
     *
     * IMPORTANT DISTINCTION FROM OTP:
     * - Returns 202 Accepted (not 200 OK)
     * - Request is queued, not processed immediately
     * - Client must poll /payment/status/{requestId} to get result
     * - This models truly asynchronous workloads
     *
     * @param userId User identifier from X-User-Id header (required)
     * @param body Request body containing payment details
     * @return Response entity with status and requestId
     */
    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processPayment(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody PaymentRequest body
    ) {
        log.info("Payment process request from user: {} for amount: {}", userId, body.amount());

        // Step 1: Validate input
        if (body.amount() <= 0) {
            log.warn("Invalid amount for user {}: {}", userId, body.amount());
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Invalid amount");
            return ResponseEntity.badRequest().body(error);
        }

        // Step 2: Check rate limit (Leaky Bucket enqueue)
        RateLimitDecision decision = rateLimiterService.checkPaymentRateLimit(
                userId,
                System.currentTimeMillis()
        );

        // Step 3: Handle decision
        if (decision.allowed()) {
            return handlePaymentAccepted(userId, body, decision);
        } else {
            return handlePaymentRejected(decision);
        }
    }

    /**
     * Handle accepted payment request (queued).
     *
     * DESIGN DECISION: 202 Accepted
     * - HTTP 202 means "accepted but not completed"
     * - Standard response for async operations
     * - Client knows to check status later
     *
     * Response includes:
     * - requestId: for polling status
     * - status: "QUEUED" (will be PROCESSING/COMPLETED/FAILED after)
     * - queueDepth: current queue depth (informational)
     *
     * @param userId User identifier
     * @param body Payment request
     * @param decision Rate limit decision (allowed=true, metadata=requestId)
     * @return 202 Accepted response
     */
    private ResponseEntity<Map<String, Object>> handlePaymentAccepted(
            String userId,
            PaymentRequest body,
            RateLimitDecision decision
    ) {
        log.debug("Payment accepted for user: {} (queued for processing)", userId);

        // Extract requestId from metadata (set by LeakyBucketRateLimiter)
        String requestId = decision.metadata().contains(":")
                ? decision.metadata().split(":")[1]
                : "unknown";

        Map<String, Object> response = new HashMap<>();
        response.put("status", "QUEUED");
        response.put("message", "Payment accepted and queued for processing");
        response.put("requestId", requestId);
        response.put("queueDepth", decision.remainingCapacity());
        response.put("amount", body.amount());

        // In Phase 3, store request details in Redis and have worker process it
        // For Phase 1, this just returns the acceptance

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(response);
    }

    /**
     * Handle rejected payment request (queue full).
     *
     * HTTP 429 Too Many Requests:
     * - Indicates rate limit/queue capacity exceeded
     * - Include Retry-After header
     * - Advise client to retry after delay
     *
     * @param decision Rate limit decision (allowed=false)
     * @return 429 Too Many Requests response
     */
    private ResponseEntity<Map<String, Object>> handlePaymentRejected(
            RateLimitDecision decision
    ) {
        log.warn(
                "Payment rate limit exceeded. Retry after {}ms",
                decision.retryAfterMillis()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Too many requests - queue is full");
        response.put("reason", decision.metadata());
        response.put("retryAfterMs", decision.retryAfterMillis());

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(decision.retryAfterMillis() / 1000))
                .body(response);
    }

    /**
     * Check payment processing status.
     *
     * PHASE 1: Stub implementation (always returns QUEUED).
     *
     * PHASE 3: Will return actual status from worker.
     * Possible states:
     * - QUEUED: In queue, not yet processing
     * - PROCESSING: Currently being processed
     * - COMPLETED: Successfully processed
     * - FAILED: Processing failed
     *
     * @param requestId Request identifier from initial /process response
     * @return Status and details
     */
    @GetMapping("/status/{requestId}")
    public ResponseEntity<Map<String, Object>> getPaymentStatus(
            @PathVariable String requestId
    ) {
        log.debug("Checking payment status for requestId: {}", requestId);

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", requestId);
        response.put("status", "QUEUED");  // Phase 1: stub
        response.put("message", "Payment is queued for processing");

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint (no rate limiting).
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "up");
        response.put("service", "payment");
        return ResponseEntity.ok(response);
    }

    /**
     * Payment request DTO.
     *
     * DESIGN DECISION: Record for immutability
     * - Clear data contract
     * - Spring automatically deserializes JSON to record
     * - Auto-generated getters (no getter overload needed)
     */
    public record PaymentRequest(
            double amount,
            String currency
    ) {
        public PaymentRequest {
            if (amount <= 0) {
                throw new IllegalArgumentException("Amount must be positive: " + amount);
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("Currency cannot be null or blank");
            }
        }

        // Allow currency default
        public PaymentRequest(double amount) {
            this(amount, "USD");
        }
    }
}