package dev.suprim.gateway.proxy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
			spec.put("description", description.length() > 10237 ? description.substring(0, 10237) + "..." : description);
		}

		if (parameters != null) {
			spec.set("inputSchema", mapper.valueToTree(parameters));
		} else {
			ObjectNode emptySchema = mapper.createObjectNode();
			emptySchema.put("type", "object");
			spec.set("inputSchema", emptySchema);
		}

		ObjectNode wrapper = mapper.createObjectNode();
		wrapper.set("toolSpecification", spec);
		return wrapper;
	}
}
