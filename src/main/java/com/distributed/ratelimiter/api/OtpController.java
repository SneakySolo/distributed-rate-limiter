package com.distributed.ratelimiter.api;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.service.RateLimiterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/otp")
public class OtpController {

    private final RateLimiterService rateLimiterService;

    public OtpController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendOtp(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody ApiModels.OtpSendRequest request
    ) {
        RateLimitDecision decision = rateLimiterService.checkOtp(userId);

        if (!decision.allowed()) {
            if (decision.message().contains("unavailable")) {
                return ResponseEntity.status(503).body(
                        new ApiModels.ErrorResponse(503, decision.message(), 0)
                );
            }
            return ResponseEntity.status(429).body(
                    new ApiModels.ErrorResponse(429, "Rate limit exceeded", decision.retryAfterMillis())
            );
        }

        // Simulate OTP send
        return ResponseEntity.ok(
                new ApiModels.OtpSendResponse(true, "OTP sent successfully")
        );
    }
}