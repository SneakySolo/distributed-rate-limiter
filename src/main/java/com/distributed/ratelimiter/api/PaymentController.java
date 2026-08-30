package com.distributed.ratelimiter.api;

import com.distributed.ratelimiter.domain.RateLimitDecision;
import com.distributed.ratelimiter.domain.RequestStatus;
import com.distributed.ratelimiter.service.PaymentStatusService;
import com.distributed.ratelimiter.service.RateLimiterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    private final RateLimiterService rateLimiterService;
    private final PaymentStatusService paymentStatusService;

    public PaymentController(RateLimiterService rateLimiterService, PaymentStatusService paymentStatusService) {
        this.rateLimiterService = rateLimiterService;
        this.paymentStatusService = paymentStatusService;
    }

    @PostMapping("/process")
    public ResponseEntity<?> processPayment(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody ApiModels.PaymentProcessRequest request
    ) {
        RateLimitDecision decision = rateLimiterService.checkPayment(userId);

        if (!decision.allowed()) {
            if (decision.message().contains("unavailable")) {
                return ResponseEntity.status(503).body(
                        new ApiModels.ErrorResponse(503, decision.message(), 0)
                );
            }
            return ResponseEntity.status(429).body(
                    new ApiModels.ErrorResponse(429, "Rate limit exceeded", 0)
            );
        }

        return ResponseEntity.status(202).body(
                new ApiModels.PaymentProcessResponse(decision.requestId(), "QUEUED", "Payment queued for processing")
        );
    }

    @GetMapping("/status/{requestId}")
    public ResponseEntity<?> getStatus(@PathVariable String requestId) {
        RequestStatus status = paymentStatusService.getStatus(requestId);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                new ApiModels.PaymentStatusResponse(requestId, status.toString())
        );
    }
}
