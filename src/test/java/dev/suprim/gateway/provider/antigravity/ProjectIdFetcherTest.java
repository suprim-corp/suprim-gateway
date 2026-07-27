package dev.suprim.gateway.provider.antigravity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIdFetcherTest {

	@Test
	void buildLoadCodeAssistBody_reportsAntigravityClient() {
		String body = ProjectIdFetcher.buildLoadCodeAssistBody();

		assertTrue(body.contains("\"ideType\":\"ANTIGRAVITY\""));
		assertTrue(body.contains("\"platform\":\"DARWIN_ARM64\""));
		assertTrue(body.contains("\"pluginType\":\"GEMINI\""));
		assertTrue(body.contains("\"mode\":\"FULL_ELIGIBILITY_CHECK\""));
		assertTrue(body.contains("\"metadata\""));
	}

	@Test
	void parseTier_prefersPaidTierOverAllowedTiers() {
		String json = """
				{"allowedTiers":[{"id":"free-tier","name":"Antigravity",
				"description":"Gemini-powered code suggestions and chat in multiple IDEs",
				"isDefault":true},{"id":"standard-tier","name":"Antigravity",
				"description":"Unlimited coding assistant with the most powerful Gemini models"}],
				"upgradeSubscriptionUri":"https://codeassist.google.com/upgrade",
				"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro",
				"description":"Google AI Pro",
				"upgradeSubscriptionUri":"https://antigravity.google/g1-upgrade",
				"availableCredits":[{"creditType":"GOOGLE_ONE_AI",
				"minimumCreditAmountForUsage":"50"}]}}
				""";
		assertEquals("Google AI Pro", ProjectIdFetcher.parseTier(json));
	}

	@Test
	void parseTier_fallsBackToCurrentTier() {
		String json = """
				{"currentTier":{"id":"free-tier","name":"Antigravity",
				"description":"Gemini-powered code suggestions and chat"},
				"allowedTiers":[{"id":"standard-tier","name":"Should not be used"}]}
				""";
		assertEquals(
				"Antigravity — Gemini-powered code suggestions and chat",
				ProjectIdFetcher.parseTier(json)
		);
	}

	@Test
	void parseTier_returnsNullWhenClientRejected() {
		String json = """
				{"allowedTiers":[{"id":"standard-tier","name":"Antigravity",
				"description":"Unlimited coding assistant"}],
				"ineligibleTiers":[{"reasonCode":"UNSUPPORTED_CLIENT",
				"tierId":"free-tier","tierName":"Antigravity"}]}
				""";
		assertNull(ProjectIdFetcher.parseTier(json));
	}

	@Test
	void parseProjectId_extractsStringProject() {
		String json = """
				{"cloudaicompanionProject":"projects/cloudaicompanion-abc123"}
				""";
		String projectId = ProjectIdFetcher.parseProjectId(json);
		assertEquals("projects/cloudaicompanion-abc123", projectId);
	}

	@Test
	void parseProjectId_extractsObjectProject() {
		String json = """
				{"cloudaicompanionProject":{"id":"projects/cloudaicompanion-obj123"}}
				""";
		String projectId = ProjectIdFetcher.parseProjectId(json);
		assertEquals("projects/cloudaicompanion-obj123", projectId);
	}

	@Test
	void parseProjectId_returnsNullWhenMissing() {
		String json = """
				{"someOtherField":"value"}
				""";
		String projectId = ProjectIdFetcher.parseProjectId(json);
		assertNull(projectId);
	}

	@Test
	void parseOnboardResponse_extractsStringProjectWhenDone() {
		String json = """
				{"done":true,"response":{"cloudaicompanionProject":"projects/cloudaicompanion-xyz"}}
				""";
		String projectId = ProjectIdFetcher.parseOnboardResponse(json);
		assertEquals("projects/cloudaicompanion-xyz", projectId);
	}

	@Test
	void parseOnboardResponse_extractsObjectProjectWhenDone() {
		String json = """
				{"done":true,"response":{"cloudaicompanionProject":{"id":"projects/obj-xyz"}}}
				""";
		String projectId = ProjectIdFetcher.parseOnboardResponse(json);
		assertEquals("projects/obj-xyz", projectId);
	}

	@Test
	void parseOnboardResponse_returnsNullWhenNotDone() {
		String json = """
				{"done":false}
				""";
		String projectId = ProjectIdFetcher.parseOnboardResponse(json);
		assertNull(projectId);
	}
}
