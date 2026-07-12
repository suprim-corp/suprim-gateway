package dev.suprim.gateway.provider.xai;

import lombok.Builder;

@Builder
public record XaiTokenResponse(String accessToken, String refreshToken, String idToken, int expiresIn) {}
