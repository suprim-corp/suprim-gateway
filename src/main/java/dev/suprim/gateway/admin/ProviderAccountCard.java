package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.StoredAccount;

/**
 * One account as the providers page shows it. Cards are displayed sorted by provider, but every
 * per-account action ({@code rename}, {@code delete}, {@code usage}) addresses the account by its
 * position in the credential store — so the store index travels with the card instead of being
 * derived from the render order.
 */
public record ProviderAccountCard(int index, StoredAccount account) {

	public String provider() {
		return account.provider() != null ? account.provider() : "UNKNOWN";
	}

	public String label() {
		if (account.name() != null) {
			return account.name();
		}
		return account.provider() != null ? account.provider() : "Unnamed";
	}
}
