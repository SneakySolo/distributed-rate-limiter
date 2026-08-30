package com.distributed.ratelimiter.service;

import com.distributed.ratelimiter.domain.RequestStatus;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentStatusService {

    private final RedisTemplate<String, String> redisTemplate;

    public PaymentStatusService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RequestStatus getStatus(String requestId) {
        String statusKey = "payment:" + requestId + ":status";
        String status = redisTemplate.opsForValue().get(statusKey);
        if (status == null) {
            return null;
        }
        return RequestStatus.valueOf(status);
    }

    public void setStatus(String requestId, RequestStatus status) {
        String statusKey = "payment:" + requestId + ":status";
        redisTemplate.opsForValue().set(statusKey, status.toString());
        redisTemplate.expire(statusKey, java.time.Duration.ofMinutes(10));
    }
}
