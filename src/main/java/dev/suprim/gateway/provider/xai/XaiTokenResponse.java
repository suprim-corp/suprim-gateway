package dev.suprim.gateway.provider.xai;

record XaiTokenResponse(String accessToken, String refreshToken, String idToken, int expiresIn) {}
