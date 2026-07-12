package dev.suprim.gateway.provider.antigravity;

public record GoogleTokenResponse(String accessToken, String refreshToken, int expiresIn) {}
