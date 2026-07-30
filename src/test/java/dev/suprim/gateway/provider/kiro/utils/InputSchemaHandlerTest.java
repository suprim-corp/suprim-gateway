package dev.suprim.gateway.provider.kiro.utils;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schemas here are the ones the upstream rejected, copied from the tool specifications in
 * {@code docs/abc.log} rather than invented, so a passing test means the exact reported payload
 * is now normalized.
 */
class InputSchemaHandlerTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	/** 2^53-1, the bound the client emits. */
	private static final long UNSAFE_MAX = 9007199254740991L;

	@Test
	void clampsOutOfRangeBoundsOnTopLevelProperties() {
		JsonNode schema = clean("""
				{
				  "type": "object",
				  "properties": {
				    "offset": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
				    "limit": {"type": "integer", "exclusiveMinimum": 0, "maximum": 9007199254740991}
				  },
				  "required": ["file_path"]
				}
				""");

		assertEquals(0, schema.at("/properties/offset/minimum").intValue());
		assertEquals(
				Integer.MAX_VALUE,
				schema.at("/properties/offset/maximum").intValue()
		);
		assertEquals(
				0,
				schema.at("/properties/limit/exclusiveMinimum").intValue()
		);
		assertEquals(
				Integer.MAX_VALUE,
				schema.at("/properties/limit/maximum").intValue()
		);
	}

	@Test
	void clampsBothEdgesInsideArrayItems() {
		JsonNode schema = clean("""
				{
				  "type": "object",
				  "properties": {
				    "findings": {
				      "type": "array",
				      "items": {
				        "type": "object",
				        "properties": {
				          "line": {
				            "type": "integer",
				            "minimum": -9007199254740991,
				            "maximum": 9007199254740991
				          }
				        }
				      }
				    }
				  }
				}
				""");

		JsonNode line = schema.at(
				"/properties/findings/items/properties/line"
		);
		assertEquals(Integer.MIN_VALUE, line.get("minimum").intValue());
		assertEquals(Integer.MAX_VALUE, line.get("maximum").intValue());
	}

	@Test
	void clampsBoundsNestedThreeLevelsDeep() {
		JsonNode schema = clean("""
				{
				  "type": "object",
				  "properties": {
				    "assets": {
				      "type": "array",
				      "items": {
				        "type": "object",
				        "properties": {
				          "viewport": {
				            "type": "object",
				            "properties": {
				              "width": {"type": "integer", "exclusiveMinimum": 0, "maximum": 9007199254740991},
				              "height": {"type": "integer", "exclusiveMinimum": 0, "maximum": 9007199254740991}
				            },
				            "required": ["width"]
				          }
				        }
				      }
				    }
				  }
				}
				""");

		JsonNode viewport = schema.at(
				"/properties/assets/items/properties/viewport/properties"
		);
		assertEquals(
				Integer.MAX_VALUE,
				viewport.at("/width/maximum").intValue()
		);
		assertEquals(
				Integer.MAX_VALUE,
				viewport.at("/height/maximum").intValue()
		);
		assertNoBoundOutsideInt32(schema);
	}

	@Test
	void leavesBoundsAlreadyWithinInt32Untouched() {
		JsonNode schema = clean("""
				{
				  "type": "object",
				  "properties": {
				    "timeout": {"type": "number", "minimum": 0, "maximum": 600000},
				    "top_k": {"type": "integer", "minimum": 1},
				    "ratio": {"type": "number", "minimum": 0.5, "maximum": 2.5}
				  }
				}
				""");

		assertEquals(0, schema.at("/properties/timeout/minimum").intValue());
		assertEquals(
				600000,
				schema.at("/properties/timeout/maximum").intValue()
		);
		assertEquals(1, schema.at("/properties/top_k/minimum").intValue());
		assertEquals(
				"0.5",
				schema.at("/properties/ratio/minimum").decimalValue().toString()
		);
		assertEquals(
				"2.5",
				schema.at("/properties/ratio/maximum").decimalValue().toString()
		);
	}

	@Test
	void clampsExactlyAtTheInt32Edge() {
		JsonNode schema = clean("""
				{
				  "properties": {
				    "at_max": {"type": "integer", "maximum": 2147483647},
				    "past_max": {"type": "integer", "maximum": 2147483648},
				    "at_min": {"type": "integer", "minimum": -2147483648},
				    "past_min": {"type": "integer", "minimum": -2147483649}
				  }
				}
				""");

		assertEquals(
				Integer.MAX_VALUE,
				schema.at("/properties/at_max/maximum").intValue()
		);
		assertEquals(
				Integer.MAX_VALUE,
				schema.at("/properties/past_max/maximum").intValue()
		);
		assertEquals(
				Integer.MIN_VALUE,
				schema.at("/properties/at_min/minimum").intValue()
		);
		assertEquals(
				Integer.MIN_VALUE,
				schema.at("/properties/past_min/minimum").intValue()
		);
	}

	@Test
	void givesADescribedPropertyWithNoTypeOne() {
		JsonNode schema = clean("""
				{
				  "type": "object",
				  "properties": {
				    "args": {"description": "Optional input value exposed to the script"}
				  }
				}
				""");

		assertEquals("object", schema.at("/properties/args/type").asString());
		assertEquals(
				"Optional input value exposed to the script",
				schema.at("/properties/args/description").asString()
		);
	}

	@Test
	void leavesAPropertyWhoseTypeIsAlreadyResolvableAlone() {
		JsonNode schema = clean("""
				{
				  "type": "object",
				  "properties": {
				    "state": {
				      "description": "New status",
				      "anyOf": [
				        {"type": "string", "enum": ["pending", "completed"]},
				        {"type": "string", "const": "deleted"}
				      ]
				    },
				    "status": {"description": "Fixed marker", "const": "proactive"},
				    "level": {"description": "Effort level", "enum": ["low", "high"]},
				    "ref": {"description": "Pointer", "$ref": "#/$defs/other"},
				    "text": {"description": "Free text", "type": "string"}
				  }
				}
				""");

		assertFalse(
				schema.at("/properties/state").has("type"),
				"anyOf already resolves the type"
		);
		assertFalse(
				schema.at("/properties/status").has("type"),
				"const already resolves the type"
		);
		assertFalse(
				schema.at("/properties/level").has("type"),
				"enum already resolves the type"
		);
		assertFalse(
				schema.at("/properties/ref").has("type"),
				"$ref already resolves the type"
		);
		assertEquals(
				"string",
				schema.at("/properties/text/type").asString()
		);
	}

	@Test
	void doesNotTypeTheContainersThatCarryNoDescription() {
		JsonNode schema = clean("""
				{
				  "type": "object",
				  "properties": {
				    "answers": {
				      "description": "User answers",
				      "type": "object",
				      "propertyNames": {"type": "string"}
				    }
				  }
				}
				""");

		assertFalse(
				schema.get("properties").has("type"),
				"the properties container is not a schema node"
		);
		assertEquals(
				"string",
				schema.at("/properties/answers/propertyNames/type").asString()
		);
	}

	@Test
	void stillDropsAdditionalPropertiesAndEmptyRequiredAtEveryDepth() {
		JsonNode schema = clean("""
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
				""");

		assertNoAdditionalProperties(schema);
		assertNoEmptyRequired(schema);
	}

	@Test
	void keepsRequiredEntriesThatAreNotEmpty() {
		JsonNode schema = clean("""
				{
				  "properties": {"path": {"type": "string"}},
				  "required": ["path"]
				}
				""");

		assertEquals("path", schema.at("/required/0").asString());
	}

	@Test
	void normalizesTheFullFailingToolSetSoNoDetectorFindingRemains() {
		JsonNode schema = clean("""
				{
				  "$schema": "https://json-schema.org/draft/2020-12/schema",
				  "type": "object",
				  "properties": {
				    "offset": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
				    "limit": {"type": "integer", "exclusiveMinimum": 0, "maximum": 9007199254740991},
				    "index": {"type": "integer", "maximum": 9007199254740991},
				    "args": {"description": "Arguments, passed verbatim"},
				    "counts": {
				      "description": "Aggregate counts",
				      "type": "object",
				      "properties": {
				        "total": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
				        "bad": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
				        "thin": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
				        "variantsIdentical": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
				        "iterations": {"type": "integer", "minimum": 0, "maximum": 9007199254740991}
				      },
				      "required": ["total"]
				    },
				    "findings": {
				      "type": "array",
				      "items": {
				        "type": "object",
				        "properties": {
				          "line": {"type": "integer", "minimum": -9007199254740991, "maximum": 9007199254740991}
				        }
				      }
				    }
				  },
				  "required": ["offset"]
				}
				""");

		assertNoBoundOutsideInt32(schema);
		assertEveryDescribedNodeHasAResolvableType(schema);
	}

	private static JsonNode clean(String json) {
		ObjectNode schema = (ObjectNode) MAPPER.readTree(json);
		InputSchemaHandler.clean(schema);
		return schema;
	}

	private static void assertNoBoundOutsideInt32(JsonNode node) {
		if (node.isObject()) {
			for (String bound : new String[]{
					"minimum",
					"maximum",
					"exclusiveMinimum",
					"exclusiveMaximum"
			}) {
				JsonNode value = node.get(bound);
				if (value == null || !value.isIntegralNumber()) {
					continue;
				}
				long magnitude = value.longValue();
				assertTrue(
						magnitude <= Integer.MAX_VALUE &&
						magnitude >= Integer.MIN_VALUE,
						bound + " must fit in int32 but was " + magnitude
				);
			}
		}
		for (JsonNode child : node) {
			assertNoBoundOutsideInt32(child);
		}
	}

	private static void assertEveryDescribedNodeHasAResolvableType(
			JsonNode node
	) {
		if (node.isObject() && node.has("description")) {
			assertTrue(
					node.has("type") || node.has("anyOf") ||
					node.has("oneOf") || node.has("allOf") ||
					node.has("$ref") || node.has("const") ||
					node.has("enum"),
					"described node must resolve to a type: " + node
			);
		}
		for (JsonNode child : node) {
			assertEveryDescribedNodeHasAResolvableType(child);
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

	private static void assertNoEmptyRequired(JsonNode node) {
		if (node.isObject()) {
			JsonNode required = node.get("required");
			if (required != null) {
				assertTrue(required.isArray(), "required must be an array");
				assertFalse(
						required.isEmpty(),
						"required must never be empty"
				);
			}
		}
		for (JsonNode child : node) {
			assertNoEmptyRequired(child);
		}
	}
}
