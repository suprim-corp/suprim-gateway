package dev.suprim.gateway.model;

import dev.suprim.gateway.model.ModelCapabilities.Effort;
import dev.suprim.gateway.model.ModelCapabilities.Support;
import dev.suprim.gateway.model.ModelCapabilities.Thinking;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wire shape of a /v1/models entry. Field names follow the Anthropic Models API so a
 * client reading {@code capabilities.image_input.supported} finds it where it expects.
 */
class ModelForListingApiSerializationTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	@Test
	void serializes_capabilitiesUnderSnakeCaseNames() {
		JsonNode json = MAPPER.valueToTree(
				ModelForListingApi.builder()
				                  .id("ag/gemini-3.1-pro-low")
				                  .object("model")
				                  .ownedBy("ANTIGRAVITY")
				                  .created(1700000000L)
				                  .maxInputTokens(1048576)
				                  .maxOutputTokens(65535)
				                  .capabilities(
						                  ModelCapabilities.builder()
						                                   .imageInput(Support.YES)
						                                   .pdfInput(Support.YES)
						                                   .audioInput(Support.NO)
						                                   .thinking(
								                                   Thinking.builder()
								                                           .supported(true)
								                                           .budgetTokens(1001)
								                                           .minBudgetTokens(128)
								                                           .build()
						                                   )
						                                   .build()
				                  )
				                  .build()
		);

		assertEquals("ag/gemini-3.1-pro-low", json.get("id").asString());
		assertEquals("model", json.get("object").asString());
		assertEquals("ANTIGRAVITY", json.get("owned_by").asString());
		assertEquals(1048576, json.get("max_input_tokens").asInt());
		assertEquals(65535, json.get("max_output_tokens").asInt());

		JsonNode capabilities = json.get("capabilities");
		assertTrue(capabilities.get("image_input").get("supported").asBoolean());
		assertTrue(capabilities.get("pdf_input").get("supported").asBoolean());
		assertFalse(capabilities.get("audio_input").get("supported").asBoolean());
		assertTrue(capabilities.get("thinking").get("supported").asBoolean());
		assertEquals(1001, capabilities.get("thinking").get("budget_tokens").asInt());
		assertEquals(128, capabilities.get("thinking").get("min_budget_tokens").asInt());
	}

	@Test
	void serializes_effortLevelsAndDefault() {
		JsonNode capabilities = MAPPER.valueToTree(
				ModelCapabilities.builder()
				                 .effort(
						                 Effort.builder()
						                       .supported(true)
						                       .levels(List.of("low", "high", "max"))
						                       .defaultLevel("high")
						                       .build()
				                 )
				                 .build()
		);

		JsonNode effort = capabilities.get("effort");
		assertTrue(effort.get("supported").asBoolean());
		assertEquals(3, effort.get("levels").size());
		assertEquals("low", effort.get("levels").get(0).asString());
		assertEquals("high", effort.get("default_level").asString());
	}

	/**
	 * A provider that reports nothing must not emit empty capability objects — an absent field
	 * means unknown, and a client should be able to tell that from an explicit false.
	 */
	@Test
	void omits_unreportedFieldsEntirely() {
		JsonNode json = MAPPER.valueToTree(
				ModelForListingApi.builder()
				                  .id("grok-4.5")
				                  .object("model")
				                  .ownedBy("XAI")
				                  .created(1700000000L)
				                  .build()
		);

		assertFalse(json.has("capabilities"));
		assertFalse(json.has("max_input_tokens"));
		assertFalse(json.has("max_output_tokens"));
		assertFalse(json.has("display_name"));
		assertEquals("grok-4.5", json.get("id").asString());
	}

	@Test
	void omits_unsetCapabilitiesWithinAReportedSet() {
		JsonNode capabilities = MAPPER.valueToTree(
				ModelCapabilities.builder().imageInput(Support.YES).build()
		);

		assertTrue(capabilities.get("image_input").get("supported").asBoolean());
		assertFalse(capabilities.has("pdf_input"));
		assertFalse(capabilities.has("thinking"));
		assertFalse(capabilities.has("effort"));
		assertFalse(capabilities.has("prompt_caching"));
	}

	/** A thinking budget the upstream did not give must not serialize as 0. */
	@Test
	void omits_thinkingBudgetWhenUnreported() {
		JsonNode thinking = MAPPER.valueToTree(
				Thinking.builder().supported(true).build()
		);

		assertTrue(thinking.get("supported").asBoolean());
		assertFalse(thinking.has("budget_tokens"));
		assertFalse(thinking.has("min_budget_tokens"));
	}
}
