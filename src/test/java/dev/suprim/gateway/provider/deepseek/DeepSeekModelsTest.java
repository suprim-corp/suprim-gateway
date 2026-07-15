package dev.suprim.gateway.provider.deepseek;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelsTest {

	@Test
	void allModels_containsExpectedModels() {
		List<String> models = DeepSeekModels.ALL;

		assertTrue(models.contains("deepseek/v4-flash"));
		assertTrue(models.contains("deepseek/v4-pro"));
		assertTrue(models.contains("deepseek/v4-flash-search"));
		assertTrue(models.contains("deepseek/v4-pro-search"));
		assertTrue(models.contains("deepseek/v4-vision"));
	}

	@Test
	void allModels_immutable() {
		assertThrows(UnsupportedOperationException.class, () -> DeepSeekModels.ALL.add("x"));
	}

	@Test
	void displayName_returnsReadableNames() {
		assertEquals("DeepSeek V4 Flash", DeepSeekModels.displayName("deepseek/v4-flash"));
		assertEquals("DeepSeek V4 Pro", DeepSeekModels.displayName("deepseek/v4-pro"));
		assertEquals("DeepSeek V4 Flash Search", DeepSeekModels.displayName("deepseek/v4-flash-search"));
		assertEquals("DeepSeek V4 Pro Search", DeepSeekModels.displayName("deepseek/v4-pro-search"));
		assertEquals("DeepSeek V4 Vision", DeepSeekModels.displayName("deepseek/v4-vision"));
	}

	@Test
	void displayName_unknownModel_returnsModelId() {
		assertEquals("deepseek/unknown", DeepSeekModels.displayName("deepseek/unknown"));
	}
}
