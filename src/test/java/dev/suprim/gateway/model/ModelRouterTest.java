package dev.suprim.gateway.model;

import dev.suprim.gateway.auth.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRouterTest {

	@Test
	void resolveProvider_geminiFlash_antigravity() {
		assertEquals(Provider.ANTIGRAVITY, ModelRouter.resolveProvider("ag/gemini-2.5-flash"));
	}

	@Test
	void resolveProvider_geminiPro_antigravity() {
		assertEquals(Provider.ANTIGRAVITY, ModelRouter.resolveProvider("ag/gemini-2.5-pro"));
	}

	@Test
	void resolveProvider_gemini20Flash_antigravity() {
		assertEquals(Provider.ANTIGRAVITY, ModelRouter.resolveProvider("ag/gemini-2.0-flash"));
	}

	@Test
	void resolveProvider_claudeSonnet_kiro() {
		assertEquals(Provider.KIRO, ModelRouter.resolveProvider("claude-sonnet-4.5"));
	}

	@Test
	void resolveProvider_claudeOpus_kiro() {
		assertEquals(Provider.KIRO, ModelRouter.resolveProvider("claude-opus-4"));
	}

	@Test
	void resolveProvider_auto_kiro() {
		assertEquals(Provider.KIRO, ModelRouter.resolveProvider("auto"));
	}

	@Test
	void resolveProvider_null_kiro() {
		assertEquals(Provider.KIRO, ModelRouter.resolveProvider(null));
	}

	@Test
	void resolveProvider_emptyString_kiro() {
		assertEquals(Provider.KIRO, ModelRouter.resolveProvider(""));
	}
}
