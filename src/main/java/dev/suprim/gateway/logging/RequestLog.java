package dev.suprim.gateway.logging;

import lombok.Builder;

@Builder
public record RequestLog(
        String id,
        String virtualKeyId,
        String accountId,
        String model,
        String requestedModel,
        int status,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer latencyMs,
        Integer firstTokenMs,
        Boolean streaming,
        String clientIp,
        String errorMessage,
        Double credits,
        long createdAt
) {}
