package dev.suprim.gateway.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderDeepSeekTest {

	@Test
	void fromModel_deepseekPrefix_returnsDeepSeek() {
		Provider result = Provider.fromModel("deepseek/v4-flash");
		assertEquals(Provider.DEEPSEEK, result);
	}

	@Test
	void fromModel_deepseekProSearch_returnsDeepSeek() {
		Provider result = Provider.fromModel("deepseek/v4-pro-search");
		assertEquals(Provider.DEEPSEEK, result);
	}

	@Test
	void stripPrefix_deepseekModel_stripsCorrectly() {
		String result = Provider.stripPrefix("deepseek/v4-flash");
		assertEquals("v4-flash", result);
	}

	@Test
	void stripPrefix_deepseekVision_stripsCorrectly() {
		String result = Provider.stripPrefix("deepseek/v4-vision");
		assertEquals("v4-vision", result);
	}
}
