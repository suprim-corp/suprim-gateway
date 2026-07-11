package dev.suprim.gateway.provider.kiro;

import lombok.Builder;

@Builder
public record ImportRequest(
		String refreshToken,
		String clientId,
		String clientSecret,
		String region,
		String profileArn
) {}
