package com.distributed.ratelimiter.domain;

public enum RequestStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}