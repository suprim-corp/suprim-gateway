package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.model.ModelResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KiroModelNamesTest {

	@Test
	void exposedId_hyphenatesDottedClaudeVersions() {
		assertEquals("claude-opus-4-6", KiroModelNames.exposedId("claude-opus-4.6"));
		assertEquals("claude-sonnet-4-6", KiroModelNames.exposedId("claude-sonnet-4.6"));
		assertEquals("claude-haiku-4-5", KiroModelNames.exposedId("claude-haiku-4.5"));
	}

	/** Only Claude ids carry a dotted version; other vendors' dots are part of the name. */
	@Test
	void exposedId_leavesOtherIdsAlone() {
		assertEquals("claude-opus-5", KiroModelNames.exposedId("claude-opus-5"));
		assertEquals("gpt-5.6-sol", KiroModelNames.exposedId("gpt-5.6-sol"));
		assertEquals("minimax-m2.5", KiroModelNames.exposedId("minimax-m2.5"));
	}

	/**
	 * The published id has to route: a request naming it must fold back onto the canonical id the
	 * upstream accepts, or every hyphenated model 404s.
	 */
	@Test
	void exposedId_roundTripsThroughTheResolver() {
		ModelResolver resolver = new ModelResolver();
		for (String canonical : List.of(
				"claude-opus-4.6",
				"claude-sonnet-4.6",
				"claude-haiku-4.5",
				"claude-opus-5",
				"gpt-5.6-sol",
				"minimax-m2.5"
		)) {
			assertEquals(
					canonical,
					resolver.canonicalize(KiroModelNames.exposedId(canonical)),
					canonical + " does not round-trip"
			);
		}
	}

	@Test
	void displayName_dropsClaudePrefixAndTitleCasesTheRest() {
		assertEquals("Opus 5", KiroModelNames.displayName("claude-opus-5"));
		assertEquals("Opus 4.8", KiroModelNames.displayName("claude-opus-4.8"));
		assertEquals("Sonnet 4.6", KiroModelNames.displayName("claude-sonnet-4.6"));
		assertEquals("Haiku 4.5", KiroModelNames.displayName("claude-haiku-4.5"));
	}

	@Test
	void displayName_usesVendorCasingForNonClaudeModels() {
		assertEquals("GPT 5.6 Sol", KiroModelNames.displayName("gpt-5.6-sol"));
		assertEquals("GLM 5", KiroModelNames.displayName("glm-5"));
		assertEquals("DeepSeek 3.2", KiroModelNames.displayName("deepseek-3.2"));
		assertEquals("MiniMax M2.5", KiroModelNames.displayName("minimax-m2.5"));
		assertEquals("Qwen3 Coder Next", KiroModelNames.displayName("qwen3-coder-next"));
	}
}
