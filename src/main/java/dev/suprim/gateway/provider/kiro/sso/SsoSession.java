package dev.suprim.gateway.provider.kiro.sso;

import lombok.Builder;

import java.time.Instant;

@Builder
public record SsoSession(
        String sessionId,
        String clientId,
        String clientSecret,
        String deviceCode,
        int interval,
        Instant expiresAt,
        String startUrl,
        String region
) {}
