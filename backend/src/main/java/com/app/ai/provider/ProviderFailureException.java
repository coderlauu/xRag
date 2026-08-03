package com.app.ai.provider;

import java.time.Duration;
import java.util.Objects;

public final class ProviderFailureException extends RuntimeException {

    private final AttemptFailureType failureType;
    private final Integer httpStatus;
    private final Duration retryAfter;
    private final String providerRequestIdMasked;

    public ProviderFailureException(
            AttemptFailureType failureType,
            Integer httpStatus,
            Duration retryAfter,
            String providerRequestIdMasked) {
        super("Provider attempt failed: " + Objects.requireNonNull(failureType));
        this.failureType = failureType;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
        this.providerRequestIdMasked = providerRequestIdMasked;
    }

    public static ProviderFailureException http(
            int status, Duration retryAfter, String providerRequestIdMasked) {
        AttemptFailureType type = switch (status) {
            case 400 -> AttemptFailureType.UPSTREAM_REQUEST_REJECTED;
            case 401 -> AttemptFailureType.AUTHENTICATION_REJECTED;
            case 403 -> AttemptFailureType.PERMISSION_REJECTED;
            case 429 -> AttemptFailureType.RATE_LIMITED_RESPONSE;
            default -> {
                if (status >= 500 && status <= 599) {
                    yield AttemptFailureType.SERVER_ERROR_RESPONSE;
                }
                throw new IllegalArgumentException("unsupported scripted HTTP status: " + status);
            }
        };
        return new ProviderFailureException(type, status, retryAfter, providerRequestIdMasked);
    }

    public static ProviderFailureException of(AttemptFailureType failureType) {
        return new ProviderFailureException(failureType, null, null, null);
    }

    public AttemptFailureType failureType() {
        return failureType;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public String providerRequestIdMasked() {
        return providerRequestIdMasked;
    }
}
