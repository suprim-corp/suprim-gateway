package dev.suprim.gateway.provider.kiro.refresher;

import lombok.Builder;

import java.time.Instant;

@Builder
public record RefreshResult(
		String accessToken,
		String refreshToken,
		Instant expiresAt,
		String profileArn
) {}
