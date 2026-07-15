package dev.suprim.gateway.provider.deepseek;

import java.util.List;
import java.util.Map;

/**
 * Available DeepSeek Web models exposed via /v1/models.
 */
public final class DeepSeekModels {

	public static final List<String> ALL = List.of(
			"deepseek/v4-flash",
			"deepseek/v4-pro",
			"deepseek/v4-flash-search",
			"deepseek/v4-pro-search",
			"deepseek/v4-vision"
	);

	private static final Map<String, String> DISPLAY_NAMES = Map.of(
			"deepseek/v4-flash", "DeepSeek V4 Flash",
			"deepseek/v4-pro", "DeepSeek V4 Pro",
			"deepseek/v4-flash-search", "DeepSeek V4 Flash Search",
			"deepseek/v4-pro-search", "DeepSeek V4 Pro Search",
			"deepseek/v4-vision", "DeepSeek V4 Vision"
	);

	private DeepSeekModels() {}

	public static String displayName(String modelId) {
		return DISPLAY_NAMES.getOrDefault(modelId, modelId);
	}
}
