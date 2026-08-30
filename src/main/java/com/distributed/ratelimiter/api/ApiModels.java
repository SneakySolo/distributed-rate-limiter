package com.distributed.ratelimiter.api;

public class ApiModels {

    public record OtpSendRequest(
            String phoneNumber
    ) {
    }

    public record OtpSendResponse(
            boolean success,
            String message
    ) {
    }

    public record PaymentProcessRequest(
            String amount,
            String currency
    ) {
    }

    public record PaymentProcessResponse(
            String requestId,
            String status,
            String message
    ) {
    }

    public record PaymentStatusResponse(
            String requestId,
            String status
    ) {
    }

    public record ErrorResponse(
            int status,
            String message,
            long retryAfterMs
    ) {
    }
}
