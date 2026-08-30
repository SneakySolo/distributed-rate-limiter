package com.distributed.ratelimiter.domain;

public record RateLimitDecision(
        boolean allowed,
        long remainingCapacity,
        long retryAfterMillis,
        String message,
        String requestId
) {
    public RateLimitDecision(boolean allowed, long remainingCapacity, long retryAfterMillis, String message) {
        this(allowed, remainingCapacity, retryAfterMillis, message, null);
    }

    public static RateLimitDecision allowed(long remaining) {
        return new RateLimitDecision(true, remaining, 0, "Request allowed", null);
    }

    public static RateLimitDecision allowedAsync(long remaining, String requestId) {
        return new RateLimitDecision(true, remaining, 0, "Request queued", requestId);
    }

    public static RateLimitDecision rejected(long retryAfterMs) {
        return new RateLimitDecision(false, 0, retryAfterMs, "Rate limit exceeded", null);
    }

    public static RateLimitDecision unavailable(String message) {
        return new RateLimitDecision(false, 0, 0, message, null);
    }
}
