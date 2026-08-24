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
 * OTP (One-Time Password) endpoint controller.
 *
 * RESPONSIBILITY:
 * - Handle HTTP requests to /otp/** endpoints
 * - Translate HTTP concerns (headers, status codes) to domain concerns
 * - Apply Token Bucket rate limiting
 * - Return appropriate HTTP responses
 *
 * DESIGN DECISION: Why thin controller?
 * - All business logic (rate limiting) is in service/algorithm layers
 * - Controller only handles HTTP translation
 * - Makes algorithms testable without HTTP framework
 * - HTTP status code logic is HERE (not in algorithm)
 *
 * ROUTING:
 * /otp/send -> Token Bucket (burst-friendly, immediate responses)
 *
 * RATE LIMIT BEHAVIOR:
 * - 100 requests per minute per user
 * - Burst: First 100 requests allowed instantly
 * - After burst: One token every 600ms
 *
 * HTTP RESPONSES:
 * 200 OK: Request allowed
 * 429 Too Many Requests: Rate limit exceeded
 * 503 Service Unavailable: Rate limiter failed (Phase 2+ Redis issues)
 */
@Slf4j
@RestController
@RequestMapping("/otp")
public class OtpController {

    private final RateLimiterService rateLimiterService;

    public OtpController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Send OTP endpoint.
     *
     * Flow:
     * 1. Extract user ID from X-User-Id header
     * 2. Check rate limit (Token Bucket)
     * 3. If allowed: send OTP, return 200
     * 4. If rejected: return 429 with retry-after
     *
     * @param userId User identifier from X-User-Id header (required)
     * @return Response entity with status and body
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendOtp(
            @RequestHeader("X-User-Id") String userId
    ) {
        log.info("OTP send request from user: {}", userId);

        // Step 1: Check rate limit
        RateLimitDecision decision = rateLimiterService.checkOtpRateLimit(
                userId,
                System.currentTimeMillis()
        );

        // Step 2: Build response based on decision
        if (decision.allowed()) {
            // Rate limit check passed - process OTP send
            return handleOtpAllowed(userId, decision);
        } else {
            // Rate limit exceeded
            return handleOtpRejected(decision);
        }
    }

    /**
     * Handle allowed OTP request.
     *
     * DESIGN DECISION: Why separate method?
     * - Clarifies success path
     * - Contains response building logic
     * - Easier to extend (e.g., integrate with SMS provider)
     *
     * @param userId User identifier
     * @param decision Rate limit decision (allowed=true)
     * @return 200 OK response
     */
    private ResponseEntity<Map<String, Object>> handleOtpAllowed(
            String userId,
            RateLimitDecision decision
    ) {
        log.debug("OTP allowed for user: {}", userId);

        // In production, actually send OTP (SMS, email, etc.)
        // For now, just simulate
        String otp = generateOtp();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "OTP sent successfully");
        response.put("otp", otp); // In production, never return OTP in response!
        response.put("remainingRequests", decision.remainingCapacity());

        return ResponseEntity.ok(response);
    }

    /**
     * Handle rejected (rate-limited) OTP request.
     *
     * DESIGN DECISION: Why separate method?
     * - Clarifies error path
     * - Centralizes HTTP 429 handling
     * - Could add metrics/logging here
     *
     * HTTP 429 Too Many Requests:
     * - Indicates rate limit exceeded
     * - Include Retry-After header (standard HTTP)
     * - Body explains reason
     *
     * @param decision Rate limit decision (allowed=false)
     * @return 429 Too Many Requests response with Retry-After header
     */
    private ResponseEntity<Map<String, Object>> handleOtpRejected(
            RateLimitDecision decision
    ) {
        log.warn(
                "OTP rate limit exceeded. Retry after {}ms",
                decision.retryAfterMillis()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Too many requests");
        response.put("reason", decision.metadata());
        response.put("retryAfterMs", decision.retryAfterMillis());

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                // Standard HTTP header: seconds (not milliseconds)
                .header("Retry-After", String.valueOf(decision.retryAfterMillis() / 1000))
                .body(response);
    }

    /**
     * Simulate OTP generation.
     *
     * In production:
     * - Generate secure random 6-digit code
     * - Store in cache with expiry
     * - Send via SMS/email
     *
     * For Phase 1: just return fake code
     */
    private String generateOtp() {
        return String.format("%06d", (int) (Math.random() * 1_000_000));
    }

    /**
     * Health check endpoint (no rate limiting).
     * Useful for monitoring.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "up");
        response.put("service", "otp");
        return ResponseEntity.ok(response);
    }
}