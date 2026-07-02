package dev.kiro.gateway.auth;

import java.time.Instant;

public record KiroCredentials(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        String profileArn,
        String clientId,
        String clientSecret,
        String[] scopes,
        AuthType authType
) {
    public enum AuthType { KIRO_DESKTOP, AWS_SSO_OIDC }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt.minusSeconds(600));
    }
}
