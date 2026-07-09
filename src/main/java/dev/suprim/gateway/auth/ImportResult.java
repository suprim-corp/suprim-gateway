package dev.suprim.gateway.auth;

import lombok.Builder;

@Builder
public record ImportResult(
		String profileArn,
		String authType,
		boolean isNew,
		StoredAccount account
) {}
