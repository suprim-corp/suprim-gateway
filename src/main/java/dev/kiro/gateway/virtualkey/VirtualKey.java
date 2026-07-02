package dev.kiro.gateway.virtualkey;

public record VirtualKey(
        String id,
        String name,
        String keyHash,
        String keyPrefix,
        String accountId,
        boolean enabled,
        Long revokedAt,
        int rateLimitPerMin,
        String allowedModels,
        String budgetPeriod,
        Integer budgetTokens,
        Integer budgetRequests,
        Integer budgetCost,
        int periodTokensUsed,
        int periodRequestsUsed,
        Long periodResetAt,
        int totalRequests,
        int totalTokens,
        Long lastUsedAt,
        long createdAt
) {}
