package dev.suprim.gateway.provider.antigravity;

record GoogleTokenResponse(String accessToken, String refreshToken, int expiresIn) {}
