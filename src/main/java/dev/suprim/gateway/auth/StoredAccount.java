package dev.suprim.gateway.auth;

import lombok.Builder;

import java.time.Instant;

@Builder
public record StoredAccount(
		String profileArn,
		String authType,
		String clientId,
		String clientSecret,
		String accessToken,
		String refreshToken,
		Instant expiresAt,
		String[] scopes,
		String region,
		String apiRegion
) {
	public StoredAccount withTokens(
			String accessToken,
			String refreshToken,
			Instant expiresAt
	) {
		return StoredAccount.builder()
		                    .profileArn(profileArn)
		                    .authType(authType)
		                    .clientId(clientId)
		                    .clientSecret(clientSecret)
		                    .accessToken(accessToken)
		                    .refreshToken(refreshToken)
		                    .expiresAt(expiresAt)
		                    .scopes(scopes)
		                    .region(region)
		                    .apiRegion(apiRegion)
		                    .build();
	}
}
