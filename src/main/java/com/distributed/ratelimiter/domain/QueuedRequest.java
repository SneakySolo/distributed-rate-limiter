package com.distributed.ratelimiter.domain;

public record QueuedRequest(
        String requestId,
        String userId,
        String service,
        long enqueuedAtMs,
        long scheduledForMs
) {
}