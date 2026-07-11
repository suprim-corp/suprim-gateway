package dev.suprim.gateway.xai;

record XaiTokenResponse(String accessToken, String refreshToken, String idToken, int expiresIn) {}
