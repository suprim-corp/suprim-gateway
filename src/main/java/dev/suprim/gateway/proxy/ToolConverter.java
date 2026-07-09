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

		String name = (String) fn.get("name");
		if (name == null || name.isBlank()) {
			return null;
		}

		ObjectNode spec = mapper.createObjectNode();
		spec.put("name", name);

		if (fn.containsKey("description")) {
			String desc = (String) fn.get("description");
			if (desc != null) {
				spec.put("description",
						desc.length() > 10237 ?
								desc.substring(0, 10237) + "..." : desc
				);
			}
		}

		if (fn.containsKey("parameters")) {
			spec.set("inputSchema", mapper.valueToTree(fn.get("parameters")));
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
