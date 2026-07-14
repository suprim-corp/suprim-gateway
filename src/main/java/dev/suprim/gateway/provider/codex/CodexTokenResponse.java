package dev.suprim.gateway.provider.codex;

import lombok.Builder;

@Builder
public record CodexTokenResponse(String accessToken, String refreshToken, String idToken, int expiresIn) {}
