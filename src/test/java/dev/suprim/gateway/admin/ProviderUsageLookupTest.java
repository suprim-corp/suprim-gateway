package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderUsageLookupTest {

	private KiroAuthManager kiro;
	private CodexAuthManager codex;
	private AntigravityAuthManager antigravity;
	private ProviderUsageLookup lookup;

	private static StoredAccount account(String provider, String name, String token) {
		return StoredAccount.builder()
		                    .provider(provider)
		                    .name(name)
		                    .accessToken(token)
		                    .build();
	}

	@BeforeEach
	void setUp() {
		kiro = mock(KiroAuthManager.class);
		codex = mock(CodexAuthManager.class);
		antigravity = mock(AntigravityAuthManager.class);
		lookup = new ProviderUsageLookup(kiro, codex, antigravity);
	}

	@Test
	void readsUsageFromTheProviderThatOwnsTheAccount() {
		when(kiro.getUsageLimits(any())).thenReturn(Map.of("usageLimit", 100));
		when(codex.getUsageLimits(any())).thenReturn(Map.of("plan", "pro"));

		assertEquals(
				100,
				lookup.forAccount(account("KIRO", "k", "token")).get("usageLimit")
		);
		assertEquals(
				"pro",
				lookup.forAccount(account("CODEX", "c", "token")).get("plan")
		);
	}

	@Test
	void skipsAccountsWithNoAccessToken() {
		assertTrue(
				lookup.forAccount(account("KIRO", "k", null)).isEmpty(),
				"Disconnected accounts cannot report usage"
		);
		verify(kiro, times(0)).getUsageLimits(any());
	}

	@Test
	void skipsProvidersWithoutAUsageEndpoint() {
		assertTrue(lookup.forAccount(account("DEEPSEEK", "d", "token")).isEmpty());
		assertTrue(lookup.forAccount(account("XAI", "x", "token")).isEmpty());
	}

	@Test
	void treatsAnUnrecognisedProviderAsReportingNothing() {
		assertTrue(lookup.forAccount(account("NOT_A_PROVIDER", "n", "token")).isEmpty());
		assertTrue(lookup.forAccount(account(null, "n", "token")).isEmpty());
	}

	@Test
	void reportsNothingWhenTheLookupThrows() {
		when(kiro.getUsageLimits(any())).thenThrow(new RuntimeException("upstream down"));

		assertTrue(
				lookup.forAccount(account("KIRO", "k", "token")).isEmpty(),
				"A failed lookup must not propagate to the page"
		);
	}

	@Test
	void servesRepeatPollsForTheSameAccountFromCache() {
		when(kiro.getUsageLimits(any())).thenReturn(Map.of("usageLimit", 100));

		lookup.forAccount(account("KIRO", "k", "token"));
		lookup.forAccount(account("KIRO", "k", "token"));

		verify(kiro, times(1)).getUsageLimits(any());
	}

	@Test
	void cachesPerAccountRatherThanPerProvider() {
		when(kiro.getUsageLimits(any())).thenReturn(Map.of("usageLimit", 100));

		lookup.forAccount(account("KIRO", "one", "token"));
		lookup.forAccount(account("KIRO", "two", "token"));

		verify(kiro, times(2)).getUsageLimits(any());
	}

	@Test
	void attachesTheSubscriptionTierToAntigravityQuota() {
		when(antigravity.getQuota(any())).thenReturn(Map.of("quota", 42));
		when(antigravity.getSubscriptionTier(any())).thenReturn("Pro");

		Map<String, Object> usage = lookup.forAccount(account("ANTIGRAVITY", "a", "token"));

		assertEquals("Pro", usage.get("tier"));
		assertEquals(42, usage.get("quota"));
	}

	@Test
	void omitsTheTierWhenAntigravityDoesNotReportOne() {
		when(antigravity.getQuota(any())).thenReturn(Map.of("quota", 42));
		when(antigravity.getSubscriptionTier(any())).thenReturn(null);

		Map<String, Object> usage = lookup.forAccount(account("ANTIGRAVITY", "a", "token"));

		assertTrue(!usage.containsKey("tier"), "A missing tier should not render as null");
	}
}
