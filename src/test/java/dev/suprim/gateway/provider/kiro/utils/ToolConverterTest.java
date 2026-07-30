package dev.suprim.gateway.provider.kiro.utils;

import dev.suprim.gateway.provider.kiro.model.KiroTool;
import dev.suprim.gateway.proxy.Tool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolConverterTest {

	@Test
	void normalizesToolForKiroRequestValidation() throws Exception {
		JsonNode parameters = new JsonMapper().readTree("{\"type\":\"object\",\"properties\":{}}");
		KiroTool converted = ToolConverter.convert(Tool.builder()
		                                               .type("function")
		                                               .function(Tool.Function.builder()
		                                                                      .name("lookup")
		                                                                      .parameters(parameters)
		                                                                      .build())
		                                               .build());

		assertEquals("Tool: lookup", converted.toolSpecification().description());
		JsonNode schema = converted.toolSpecification().inputSchema().json();
		assertEquals("object", schema.get("type").asString());
		assertTrue(schema.get("properties").isObject());
		assertTrue(schema.get("required").isArray());
	}
}
