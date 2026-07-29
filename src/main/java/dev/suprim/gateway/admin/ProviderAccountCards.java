package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.StoredAccount;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Builds the providers page card list: store order in, provider-alphabetical order out.
 */
final class ProviderAccountCards {

	private static final Comparator<ProviderAccountCard> BY_PROVIDER_THEN_NAME =
			Comparator.comparing(
					          ProviderAccountCard::provider,
					          String.CASE_INSENSITIVE_ORDER
			          )
			          .thenComparing(
					          ProviderAccountCard::label,
					          String.CASE_INSENSITIVE_ORDER
			          );

	private ProviderAccountCards() {}

	static List<ProviderAccountCard> sorted(List<StoredAccount> accounts) {
		return IntStream.range(0, accounts.size())
		                .mapToObj(i -> new ProviderAccountCard(i, accounts.get(i)))
		                .sorted(BY_PROVIDER_THEN_NAME)
		                .toList();
	}

	static List<ProviderAccountCard> filtered(
			List<ProviderAccountCard> cards,
			String provider
	) {
		if (provider == null || provider.isBlank()) {
			return cards;
		}
		return cards.stream()
		            .filter(card -> provider.equals(card.provider()))
		            .toList();
	}

	/**
	 * Provider names present in the list, alphabetical — the filter only offers what is there.
	 */
	static List<String> providers(List<ProviderAccountCard> cards) {
		return cards.stream()
		            .map(ProviderAccountCard::provider)
		            .distinct()
		            .sorted(String.CASE_INSENSITIVE_ORDER)
		            .toList();
	}
}
