package dev.suprim.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;

final class ToolConverter {

	private ToolConverter() {}

	static ObjectNode toKiroTool(JsonNode tool, ObjectMapper mapper) {
		String type = Optional.ofNullable(tool.get("type"))
		                      .map(JsonNode::stringValue)
		                      .orElse("");
		if (!"function".equals(type)) {
			return null;
		}

		String name;
		String description;
		JsonNode parameters;

		JsonNode fn = tool.get("function");
		if (fn != null && fn.isObject()) {
			name = Optional.ofNullable(fn.get("name"))
			               .map(JsonNode::stringValue)
			               .orElse(null);
			description = Optional.ofNullable(fn.get("description"))
			                      .map(JsonNode::stringValue)
			                      .orElse(null);
			parameters = fn.get("parameters");
		} else {
			name = Optional.ofNullable(tool.get("name"))
			               .map(JsonNode::stringValue)
			               .orElse(null);
			description = Optional.ofNullable(tool.get("description"))
			                      .map(JsonNode::stringValue)
			                      .orElse(null);
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
		if (parameters != null && parameters.isObject()) {
			ObjectNode schemaNode = parameters.deepCopy().asObject();
			cleanSchema(schemaNode);
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
