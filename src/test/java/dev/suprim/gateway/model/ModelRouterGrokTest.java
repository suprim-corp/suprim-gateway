package dev.suprim.gateway.model;

import dev.suprim.gateway.auth.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRouterGrokTest {

	@Test
	void resolveProvider_grokPrefix_returnsGrok() {
		assertEquals(Provider.GROK, ModelRouter.resolveProvider("grok/grok-4"));
	}

	@Test
	void resolveProvider_grokPrefixFastReasoning_returnsGrok() {
		assertEquals(Provider.GROK, ModelRouter.resolveProvider("grok/grok-4-fast-reasoning"));
	}

	@Test
	void resolveProvider_grokPrefixOnly_returnsGrok() {
		assertEquals(Provider.GROK, ModelRouter.resolveProvider("grok/"));
	}

	@Test
	void resolveProvider_grokNoSlash_kiro() {
		assertEquals(Provider.KIRO, ModelRouter.resolveProvider("grok-4"));
	}

	@Test
	void stripPrefix_removesGrokSlash() {
		assertEquals("grok-4", ModelRouter.stripPrefix("grok/grok-4"));
	}

	@Test
	void stripPrefix_nonGrok_stripsItsOwnPrefix() {
		assertEquals("2.5-flash", ModelRouter.stripPrefix("gemini-2.5-flash"));
	}
}
