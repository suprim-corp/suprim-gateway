package dev.suprim.gateway.provider;

import lombok.Builder;

import java.time.Instant;

@Builder
public record StoredAccount(
		String name,
		String profileArn,
		String authType,
		String clientId,
		String clientSecret,
		String accessToken,
		String refreshToken,
		Instant expiresAt,
		String[] scopes,
		String region,
		String apiRegion,
		String provider,
		String projectId
) {
	public StoredAccount withTokens(
			String accessToken,
			String refreshToken,
			Instant expiresAt
	) {
		return StoredAccount.builder()
		                    .name(name)
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
		                    .provider(provider)
		                    .projectId(projectId)
		                    .build();
	}

	public StoredAccount withName(String name) {
		return StoredAccount.builder()
		                    .name(name)
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
		                    .provider(provider)
		                    .projectId(projectId)
		                    .build();
	}
}
