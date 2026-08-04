package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.antigravity.AntigravityQuota;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.codex.CodexUsage;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderUsageLookupTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

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

	private static CodexUsage codexUsage(String plan) {
		return CodexUsage.builder().plan(plan).build();
	}

	private static AntigravityQuota antigravityQuota(int percentRemaining) {
		return AntigravityQuota.builder().quota(percentRemaining).build();
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
		when(codex.getUsageLimits(any())).thenReturn(codexUsage("pro"));

		assertEquals(
				100,
				lookup.forAccount(account("KIRO", "k", "token")).path("usageLimit").asInt()
		);
		assertEquals(
				"pro",
				lookup.forAccount(account("CODEX", "c", "token")).path("plan").asString()
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
		when(antigravity.getQuota(any())).thenReturn(antigravityQuota(42));
		when(antigravity.getSubscriptionTier(any())).thenReturn("Pro");

		JsonNode usage = lookup.forAccount(account("ANTIGRAVITY", "a", "token"));

		assertEquals("Pro", usage.path("tier").asString());
		assertEquals(42, usage.path("quota").asInt());
	}

	@Test
	void passesTheRejectionFlagThroughToThePage() {
		when(codex.getUsageLimits(any())).thenReturn(
				CodexUsage.rejected("Usage unavailable (401)")
		);

		assertTrue(
				lookup.forAccount(account("CODEX", "c", "token"))
				      .path("unauthorized")
				      .asBoolean(),
				"The page cannot flip the badge if the flag stops here"
		);
	}

	@Test
	void omitsTheRejectionFlagForAnUpstreamThatIsMerelyDown() {
		when(codex.getUsageLimits(any())).thenReturn(
				CodexUsage.failure("Usage unavailable (503)")
		);

		assertTrue(
				lookup.forAccount(account("CODEX", "c", "token"))
				      .path("unauthorized")
				      .isMissingNode(),
				"A 5xx must not reach the page as a rejected credential"
		);
	}

	@Test
	void holdsARejectionRatherThanRepollingARefusedUpstream() {
		when(codex.getUsageLimits(any())).thenReturn(CodexUsage.rejected("refused"));

		lookup.forAccount(account("CODEX", "c", "token"));
		lookup.forAccount(account("CODEX", "c", "token"));

		verify(codex, times(1)).getUsageLimits(any());
	}

	@Test
	void holdsARejectionLongerThanLiveFigures() {
		Duration rejection = ProviderUsageLookup.retentionFor(
				MAPPER.valueToTree(CodexUsage.rejected("refused"))
		);
		Duration figures = ProviderUsageLookup.retentionFor(
				MAPPER.valueToTree(codexUsage("pro"))
		);

		assertEquals(Duration.ofSeconds(30), rejection);
		assertEquals(
				Duration.ofSeconds(4),
				figures,
				"Live figures must expire within one poll interval or the page shows stale numbers"
		);
	}

	@Test
	void treatsAFailedButNotRejectedLookupAsLiveFigures() {
		assertEquals(
				Duration.ofSeconds(4),
				ProviderUsageLookup.retentionFor(
						MAPPER.valueToTree(CodexUsage.failure("Usage unavailable (503)"))
				),
				"An upstream that is merely down must be retried on the next poll"
		);
	}

	@Test
	void omitsTheTierWhenAntigravityDoesNotReportOne() {
		when(antigravity.getQuota(any())).thenReturn(antigravityQuota(42));
		when(antigravity.getSubscriptionTier(any())).thenReturn(null);

		JsonNode usage = lookup.forAccount(account("ANTIGRAVITY", "a", "token"));

		assertTrue(usage.path("tier").isMissingNode(), "A missing tier should not render as null");
	}
}
