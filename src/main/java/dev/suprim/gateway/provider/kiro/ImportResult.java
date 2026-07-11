package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.StoredAccount;

import lombok.Builder;

@Builder
public record ImportResult(
		String profileArn,
		String authType,
		boolean isNew,
		StoredAccount account
) {}
