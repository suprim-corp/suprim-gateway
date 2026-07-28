package dev.suprim.gateway.provider.kiro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@code ListAvailableModels} model list into the entries callers consume, dropping
 * models the gateway hides or the operator disabled.
 * <p>
 * Capability fields are copied through as the upstream reported them: fields it omits are left
 * out rather than defaulted, so "unsupported" stays distinguishable from "unreported".
 */
final class KiroModelListing {

	/**
	 * Models the upstream offers that the gateway does not expose.
	 */
	private static final Set<String> HIDDEN_MODELS = Set.of(
			"auto",
			"claude-3.7-sonnet"
	);

	private KiroModelListing() {}

	/**
	 * Flattens the upstream models, skipping hidden and disabled ids and keeping the first entry
	 * seen for a given exposed id.
	 */
	static List<Map<String, Object>> parse(
			List<Map<String, Object>> upstreamModels,
			Set<String> disabledModels
	) {
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<Map<String, Object>> models = new ArrayList<>();

		for (Map<String, Object> upstream : upstreamModels) {
			if (!(upstream.get("modelId") instanceof String id) ||
			    disabledModels.contains(id) || HIDDEN_MODELS.contains(id)) {
				continue;
			}
			String exposedId = KiroModelNames.exposedId(id);
			if (disabledModels.contains(exposedId) || !seen.add(exposedId)) {
				continue;
			}
			models.add(toEntry(exposedId, upstream));
		}
		return models;
	}

	/**
	 * One upstream model as a flat entry: identity and rate first, then whatever capabilities
	 * the upstream reported — {@code supportedInputTypes}, prompt caching, token limits, and
	 * the reasoning-effort levels from {@code additionalModelRequestFieldsSchema}.
	 */
	private static Map<String, Object> toEntry(
			String exposedId,
			Map<String, Object> upstream
	) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("id", exposedId);
		entry.put("cost", orDefault(upstream.get("rateMultiplier"), 0));
		entry.put("unit", orDefault(upstream.get("rateUnit"), ""));
		entry.put("name", orDefault(upstream.get("modelName"), ""));

		if (upstream.get("supportedInputTypes") instanceof List<?> inputTypes) {
			entry.put("supportsImages", inputTypes.contains("IMAGE"));
		}
		if (upstream.get("promptCaching") instanceof Map<?, ?> caching &&
		    caching.get("supportsPromptCaching") instanceof Boolean supported) {
			entry.put("supportsPromptCaching", supported);
		}
		if (upstream.get("tokenLimits") instanceof Map<?, ?> limits) {
			putInt(entry, "maxInputTokens", limits.get("maxInputTokens"));
			putInt(entry, "maxOutputTokens", limits.get("maxOutputTokens"));
		}
		KiroEffortSchema.copyLevels(upstream, entry);
		return entry;
	}

	private static Object orDefault(Object value, Object fallback) {
		return value != null ? value : fallback;
	}

	private static void putInt(
			Map<String, Object> entry,
			String key,
			Object value
	) {
		if (value instanceof Number number) {
			entry.put(key, number.intValue());
		}
	}
}
