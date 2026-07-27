package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.StoredAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderAccountCardsTest {

	private static StoredAccount account(String provider, String name) {
		return StoredAccount.builder().provider(provider).name(name).build();
	}

	@Test
	void sortsByProviderThenName() {
		List<ProviderAccountCard> cards = ProviderAccountCards.sorted(List.of(
				account("KIRO", "zeta"),
				account("CODEX", "one"),
				account("KIRO", "alpha")
		));

		assertEquals(
				List.of("CODEX|one", "KIRO|alpha", "KIRO|zeta"),
				cards.stream().map(c -> c.provider() + "|" + c.label()).toList()
		);
	}

	@Test
	void keepsTheCredentialStoreIndexAfterSorting() {
		List<ProviderAccountCard> cards = ProviderAccountCards.sorted(List.of(
				account("KIRO", "kiro"),
				account("ANTIGRAVITY", "ag")
		));

		assertEquals(List.of(1, 0), cards.stream().map(ProviderAccountCard::index).toList());
	}

	@Test
	void sortsCaseInsensitively() {
		List<ProviderAccountCard> cards = ProviderAccountCards.sorted(List.of(
				account("KIRO", "Beta"),
				account("KIRO", "alpha")
		));

		assertEquals(List.of("alpha", "Beta"), cards.stream().map(ProviderAccountCard::label).toList());
	}

	@Test
	void fallsBackToProviderNameForUnnamedAccounts() {
		List<ProviderAccountCard> cards =
				ProviderAccountCards.sorted(List.of(account("CODEX", null)));

		assertEquals("CODEX", cards.getFirst().label());
	}

	@Test
	void treatsAMissingProviderAsUnknownRatherThanFailing() {
		List<ProviderAccountCard> cards =
				ProviderAccountCards.sorted(List.of(account(null, "orphan")));

		assertEquals("UNKNOWN", cards.getFirst().provider());
	}

	@Test
	void listsEachProviderOnceForTheFilter() {
		List<ProviderAccountCard> cards = ProviderAccountCards.sorted(List.of(
				account("KIRO", "a"),
				account("KIRO", "b"),
				account("ANTIGRAVITY", "c")
		));

		assertEquals(List.of("ANTIGRAVITY", "KIRO"), ProviderAccountCards.providers(cards));
	}
}
