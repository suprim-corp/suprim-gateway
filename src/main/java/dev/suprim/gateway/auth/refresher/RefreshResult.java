package dev.suprim.gateway.auth.refresher;

import lombok.Builder;

import java.time.Instant;

@Builder
public record RefreshResult(
		String accessToken,
		String refreshToken,
		Instant expiresAt,
		String profileArn
) {}
