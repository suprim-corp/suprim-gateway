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

		Map<String, Object> fn = (Map<String, Object>) tool.get("function");

		if (fn == null) {
			return null;
		}

		ObjectNode node = mapper.createObjectNode();
		node.put("name", (String) fn.get("name"));

		if (fn.containsKey("description")) {
			node.put(
					"description",
					(String) fn.get("description")
			);
		}

		if (fn.containsKey("parameters")) {
			node.set("inputSchema", mapper.valueToTree(fn.get("parameters")));
		}

		return node;
	}
}
