package dev.suprim.gateway.provider.kiro.reader;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.kiro.KiroCredentials;
import dev.suprim.gateway.provider.StoredAccount;

import java.util.List;
import java.util.Optional;

public final class CredentialStoreReader {

	private CredentialStoreReader() {}

	public static Optional<KiroCredentials> read(CredentialStore store) {
		if (!store.exists()) return Optional.empty();
		List<StoredAccount> accounts = store.load();
		if (accounts.isEmpty()) return Optional.empty();

		StoredAccount acc = accounts.getFirst();

		boolean hasIdAndSecret =
				acc.clientId() != null && acc.clientSecret() != null;

		KiroCredentials.AuthType authType = hasIdAndSecret
				? KiroCredentials.AuthType.AWS_SSO_OIDC
				: KiroCredentials.AuthType.KIRO_DESKTOP;

		return Optional.of(
				KiroCredentials.builder()
				               .accessToken(acc.accessToken())
				               .refreshToken(acc.refreshToken())
				               .expiresAt(acc.expiresAt())
				               .profileArn(acc.profileArn())
				               .clientId(acc.clientId())
				               .clientSecret(acc.clientSecret())
				               .scopes(acc.scopes())
				               .authType(authType)
				               .build()
		);
	}
}
