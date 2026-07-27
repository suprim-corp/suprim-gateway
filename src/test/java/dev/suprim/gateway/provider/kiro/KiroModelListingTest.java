package dev.suprim.gateway.provider.kiro;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsing of {@code ListAvailableModels} entries. The upstream shapes here are trimmed from live
 * captures.
 */
class KiroModelListingTest {

	@Test
	void parse_carriesInputTypesCachingAndTokenLimits() {
		Map<String, Object> model = parseOne(
				Map.of(
						"modelId", "claude-sonnet-5",
						"modelName", "Claude Sonnet 5",
						"rateMultiplier", 1.0,
						"rateUnit", "Credit",
						"supportedInputTypes", List.of("TEXT", "IMAGE"),
						"promptCaching", Map.of("supportsPromptCaching", true),
						"tokenLimits", Map.of(
								"maxInputTokens", 1000000,
								"maxOutputTokens", 64000
						)
				)
		);

		assertEquals("claude-sonnet-5", model.get("id"));
		assertEquals("Claude Sonnet 5", model.get("name"));
		assertEquals(1.0, model.get("cost"));
		assertEquals("Credit", model.get("unit"));
		assertEquals(true, model.get("supportsImages"));
		assertEquals(true, model.get("supportsPromptCaching"));
		assertEquals(1000000, model.get("maxInputTokens"));
		assertEquals(64000, model.get("maxOutputTokens"));
	}

	@Test
	void parse_textOnlyModel_reportsImagesUnsupported() {
		Map<String, Object> model = parseOne(
				Map.of(
						"modelId", "glm-5",
						"supportedInputTypes", List.of("TEXT"),
						"promptCaching", Map.of("supportsPromptCaching", false)
				)
		);

		assertEquals(false, model.get("supportsImages"));
		assertEquals(false, model.get("supportsPromptCaching"));
	}

	/** Claude models nest the effort enum under {@code output_config}. */
	@Test
	void parse_readsEffortLevelsFromOutputConfigSchema() {
		Map<String, Object> model = parseOne(
				Map.of(
						"modelId", "claude-opus-5",
						"additionalModelRequestFieldsSchema", effortSchema(
								"output_config",
								List.of("low", "medium", "high", "xhigh", "max"),
								"high"
						)
				)
		);

		assertEquals(
				List.of("low", "medium", "high", "xhigh", "max"),
				model.get("effortLevels")
		);
		assertEquals("high", model.get("defaultEffort"));
	}

	/** GPT models nest the same enum under {@code reasoning} instead. */
	@Test
	void parse_readsEffortLevelsFromReasoningSchema() {
		Map<String, Object> model = parseOne(
				Map.of(
						"modelId", "gpt-5.6-sol",
						"additionalModelRequestFieldsSchema", effortSchema(
								"reasoning",
								List.of("none", "low", "high"),
								"high"
						)
				)
		);

		assertEquals(List.of("none", "low", "high"), model.get("effortLevels"));
		assertEquals("high", model.get("defaultEffort"));
	}

	@Test
	void parse_schemaWithoutEffortEnum_reportsNoLevels() {
		Map<String, Object> model = parseOne(
				Map.of(
						"modelId", "some-model",
						"additionalModelRequestFieldsSchema", Map.of(
								"properties", Map.of("max_tokens", Map.of("type", "integer"))
						)
				)
		);

		assertFalse(model.containsKey("effortLevels"));
		assertFalse(model.containsKey("defaultEffort"));
	}

	/**
	 * An upstream that reports no capabilities must not gain invented keys — absent has to stay
	 * distinguishable from unsupported downstream.
	 */
	@Test
	void parse_modelWithoutCapabilities_omitsThoseKeys() {
		Map<String, Object> model = parseOne(
				Map.of("modelId", "some-future-model", "modelName", "Future")
		);

		assertEquals("some-future-model", model.get("id"));
		assertEquals(0, model.get("cost"));
		assertEquals("", model.get("unit"));
		assertFalse(model.containsKey("supportsImages"));
		assertFalse(model.containsKey("supportsPromptCaching"));
		assertFalse(model.containsKey("maxInputTokens"));
	}

	/** The dotted-version rename must not drop the capability fields along the way. */
	@Test
	void parse_hyphenatesDottedVersionAndKeepsCapabilities() {
		Map<String, Object> model = parseOne(
				Map.of(
						"modelId", "claude-sonnet-4.5",
						"supportedInputTypes", List.of("TEXT", "IMAGE"),
						"tokenLimits", Map.of("maxInputTokens", 200000)
				)
		);

		assertEquals("claude-sonnet-4-5", model.get("id"));
		assertEquals(true, model.get("supportsImages"));
		assertEquals(200000, model.get("maxInputTokens"));
	}

	/** Only Claude ids carry a dotted version; other providers' dots are part of the name. */
	@Test
	void parse_leavesNonClaudeDottedIdsAlone() {
		assertEquals("minimax-m2.5", parseOne(Map.of("modelId", "minimax-m2.5")).get("id"));
		assertEquals("gpt-5.6-sol", parseOne(Map.of("modelId", "gpt-5.6-sol")).get("id"));
	}

	@Test
	void parse_dropsHiddenModels() {
		List<Map<String, Object>> models = KiroModelListing.parse(
				List.of(
						Map.of("modelId", "auto"),
						Map.of("modelId", "claude-3.7-sonnet"),
						Map.of("modelId", "claude-sonnet-5")
				),
				Set.of()
		);

		assertEquals(1, models.size());
		assertEquals("claude-sonnet-5", models.getFirst().get("id"));
	}

	@Test
	void parse_dropsDisabledModelsByUpstreamAndExposedId() {
		List<Map<String, Object>> models = KiroModelListing.parse(
				List.of(
						Map.of("modelId", "claude-opus-5"),
						Map.of("modelId", "claude-sonnet-4.5"),
						Map.of("modelId", "glm-5")
				),
				Set.of("claude-opus-5", "claude-sonnet-4-5")
		);

		assertEquals(List.of("glm-5"), models.stream().map(m -> m.get("id")).toList());
	}

	/** Two upstream ids can rename onto one exposed id; the first wins. */
	@Test
	void parse_keepsFirstEntryForDuplicateExposedId() {
		List<Map<String, Object>> models = KiroModelListing.parse(
				List.of(
						Map.of("modelId", "claude-sonnet-4.5", "modelName", "First"),
						Map.of("modelId", "claude-sonnet-4-5", "modelName", "Second")
				),
				Set.of()
		);

		assertEquals(1, models.size());
		assertEquals("First", models.getFirst().get("name"));
	}

	@Test
	void parse_skipsEntriesWithoutAModelId() {
		List<Map<String, Object>> models = KiroModelListing.parse(
				List.of(Map.of("modelName", "Nameless"), Map.of("modelId", "glm-5")),
				Set.of()
		);

		assertEquals(List.of("glm-5"), models.stream().map(m -> m.get("id")).toList());
	}

	private static Map<String, Object> parseOne(Map<String, Object> upstream) {
		return KiroModelListing.parse(List.of(upstream), Set.of()).getFirst();
	}

	private static Map<String, Object> effortSchema(
			String wrapper,
			List<String> levels,
			String defaultLevel
	) {
		return Map.of(
				"type", "object",
				"properties", Map.of(
						wrapper, Map.of(
								"type", "object",
								"properties", Map.of(
										"effort", Map.of(
												"type", "string",
												"enum", levels,
												"default", defaultLevel
										)
								)
						)
				)
		);
	}
}
