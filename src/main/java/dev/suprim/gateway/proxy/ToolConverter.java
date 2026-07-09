package dev.suprim.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

final class ToolConverter {

	private ToolConverter() {}

	@SuppressWarnings("unchecked")
	static ObjectNode toKiroTool(
			Map<String, Object> tool,
			ObjectMapper mapper
	) {
		if (!"function".equals(tool.get("type"))) {
			return null;
		}

		String name;
		String description;
		Object parameters;

		// OpenAI chat format: {type: "function", function: {name, description, parameters}}
		Map<String, Object> fn = (Map<String, Object>) tool.get("function");
		if (fn != null) {
			name = (String) fn.get("name");
			description = (String) fn.get("description");
			parameters = fn.get("parameters");
		} else {
			// Responses API format: {type: "function", name, description, parameters}
			name = (String) tool.get("name");
			description = (String) tool.get("description");
			parameters = tool.get("parameters");
		}

		if (name == null || name.isBlank()) {
			return null;
		}

		ObjectNode spec = mapper.createObjectNode();
		spec.put("name", name);

		if (description != null) {
			spec.put(
					"description",
					description.length() > 10237 ?
							description.substring(0, 10237) +
							"..." : description
			);
		}

		ObjectNode inputSchema = mapper.createObjectNode();
		if (parameters != null) {
			JsonNode schemaNode = mapper.valueToTree(parameters);
			if (schemaNode.isObject()) {
				cleanSchema((ObjectNode) schemaNode);
			}
			inputSchema.set("json", schemaNode);
		} else {
			ObjectNode emptyObj = mapper.createObjectNode();
			emptyObj.put("type", "object");
			inputSchema.set("json", emptyObj);
		}
		spec.set("inputSchema", inputSchema);

		ObjectNode wrapper = mapper.createObjectNode();
		wrapper.set("toolSpecification", spec);
		return wrapper;
	}

	private static void cleanSchema(ObjectNode node) {
		node.remove("additionalProperties");

		// required must be non-empty array
		JsonNode required = node.get("required");
		if (required != null) {
			if (!required.isArray() || required.isEmpty()) {
				node.remove("required");
			}
		}

		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			JsonNode value = entry.getValue();
			if (value.isObject()) {
				cleanSchema((ObjectNode) value);
			} else if (value.isArray()) {
				for (JsonNode item : value) {
					if (item.isObject()) {
						cleanSchema((ObjectNode) item);
					}
				}
			}
		}
	}
}
