package dev.suprim.gateway.provider.kiro.utils;

import dev.suprim.gateway.provider.kiro.model.KiroTool;
import dev.suprim.gateway.proxy.Tool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolConverterTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	@Test
	void normalizesToolForKiroRequestValidation() {
		ToolConverter.ConversionResult result = ToolConverter.convert(List.of(
				tool("lookup", null, "{\"type\":\"object\",\"properties\":{}}")
		));
		KiroTool converted = result.tools().getFirst();

		assertEquals("Tool: lookup", converted.toolSpecification().description());
		JsonNode schema = converted.toolSpecification().inputSchema().json();
		assertEquals("object", schema.get("type").asString());
		assertTrue(schema.get("properties").isObject());
		assertFalse(schema.has("required"));
		assertEquals("", result.documentation());
	}

	@Test
	void neverEmitsEmptyRequiredAndDropsAdditionalProperties() {
		ToolConverter.ConversionResult result = ToolConverter.convert(List.of(
				tool("empty_required", null, """
						{
						  "type": "object",
						  "properties": {
						    "target": {
						      "type": "object",
						      "properties": {"id": {"type": "string"}},
						      "required": [],
						      "additionalProperties": false
						    },
						    "items": {
						      "type": "array",
						      "items": {"type": "object", "required": [], "additionalProperties": true}
						    }
						  },
						  "required": [],
						  "additionalProperties": false
						}
						"""),
				tool("no_parameters", null, null)
		));

		for (KiroTool converted : result.tools()) {
			JsonNode schema = converted.toolSpecification().inputSchema().json();
			assertRequiredNeverEmpty(schema);
			assertNoAdditionalProperties(schema);
		}
		assertEquals(2, result.tools().size());
	}

	@Test
	void keepsRequiredEntriesThatAreNotEmpty() {
		ToolConverter.ConversionResult result = ToolConverter.convert(List.of(
				tool(
						"read_file",
						null,
						"{\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"
				)
		));
		JsonNode schema = result.tools()
		                        .getFirst()
		                        .toolSpecification()
		                        .inputSchema()
		                        .json();

		assertEquals("path", schema.at("/required/0").asString());
	}

	@Test
	void movesOversizedDescriptionIntoDocumentation() {
		String longDescription = "D".repeat(10_001);
		ToolConverter.ConversionResult result = ToolConverter.convert(List.of(
				tool("workflow", longDescription, null),
				tool("short", "short description", null)
		));

		assertEquals(
				"[Full documentation in system prompt under '## Tool: workflow']",
				result.tools().getFirst().toolSpecification().description()
		);
		assertEquals(
				"short description",
				result.tools().get(1).toolSpecification().description()
		);
		assertEquals(
				"## Tool: workflow\n\n" + longDescription,
				result.documentation()
		);
	}

	@Test
	void keepsDescriptionAtThresholdInlineAndDocumentationEmpty() {
		String description = "D".repeat(10_000);
		ToolConverter.ConversionResult result = ToolConverter.convert(List.of(
				tool("workflow", description, null)
		));

		assertEquals(
				description,
				result.tools().getFirst().toolSpecification().description()
		);
		assertEquals("", result.documentation());
	}

	@Test
	void producesDeterministicDocumentationForSameTools() {
		List<Tool> tools = List.of(
				tool("first", "A".repeat(10_001), null),
				tool("second", "B".repeat(10_001), null)
		);

		assertEquals(
				ToolConverter.convert(tools).documentation(),
				ToolConverter.convert(tools).documentation()
		);
		assertEquals(
				"## Tool: first\n\n" + "A".repeat(10_001) +
				"\n\n## Tool: second\n\n" + "B".repeat(10_001),
				ToolConverter.convert(tools).documentation()
		);
	}

	private static void assertRequiredNeverEmpty(JsonNode node) {
		if (node.isObject()) {
			JsonNode required = node.get("required");
			if (required != null) {
				assertTrue(required.isArray(), "required must be an array");
				assertFalse(required.isEmpty(), "required must never be empty");
			}
		}
		for (JsonNode child : node) {
			assertRequiredNeverEmpty(child);
		}
	}

	private static void assertNoAdditionalProperties(JsonNode node) {
		if (node.isObject()) {
			assertFalse(
					node.has("additionalProperties"),
					"additionalProperties must be removed"
			);
		}
		for (JsonNode child : node) {
			assertNoAdditionalProperties(child);
		}
	}

	private static Tool tool(
			String name,
			String description,
			String parameters
	) {
		return Tool.builder()
		           .type("function")
		           .function(Tool.Function.builder()
		                                  .name(name)
		                                  .description(description)
		                                  .parameters(
				                                  parameters == null
						                                  ? null
						                                  : MAPPER.readTree(
								                                  parameters
						                                  )
		                                  )
		                                  .build())
		           .build();
	}
}
