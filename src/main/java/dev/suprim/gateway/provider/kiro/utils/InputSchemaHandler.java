package dev.suprim.gateway.provider.kiro.utils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

public class InputSchemaHandler {
	private static final JsonMapper MAPPER = new JsonMapper();

	public static JsonNode buildSchemaJson(JsonNode parameters) {
		if (parameters != null && parameters.isObject()) {
			ObjectNode schemaNode = parameters.deepCopy().asObject();
			clean(schemaNode);
			return schemaNode;
		}

		ObjectNode emptyObj = MAPPER.createObjectNode();
		emptyObj.put("type", "object");
		return emptyObj;
	}

	public static void clean(ObjectNode node) {
		node.remove("additionalProperties");

		JsonNode required = node.get("required");
		if (required != null) {
			if (!required.isArray() || required.isEmpty()) {
				node.remove("required");
			}
		}

		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			JsonNode value = entry.getValue();
			if (value.isObject()) {
				clean((ObjectNode) value);
			} else if (value.isArray()) {
				for (JsonNode item : value) {
					if (item.isObject()) {
						clean((ObjectNode) item);
					}
				}
			}
		}
	}
}
