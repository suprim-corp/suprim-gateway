package dev.suprim.gateway.provider.antigravity;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

class AntigravityPayloadBuilder {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int DEFAULT_MAX_OUTPUT_TOKENS = 65536;

	@SuppressWarnings("unchecked")
	static String build(Map<String, Object> openAiRequest, String projectId) {
		List<Map<String, Object>> messages = (List<Map<String, Object>>) openAiRequest.get("messages");

		ObjectNode root = MAPPER.createObjectNode();
		ArrayNode contents = root.putArray("contents");
		ObjectNode systemInstruction = null;

		for (Map<String, Object> msg : messages) {
			String role = (String) msg.get("role");
			String content = (String) msg.get("content");

			if ("system".equals(role)) {
				systemInstruction = MAPPER.createObjectNode();
				ArrayNode parts = systemInstruction.putArray("parts");
				parts.addObject().put("text", content);
				continue;
			}

			String geminiRole = "assistant".equals(role) ? "model" : role;
			ObjectNode entry = contents.addObject();
			entry.put("role", geminiRole);
			ArrayNode parts = entry.putArray("parts");
			parts.addObject().put("text", content);
		}

		if (systemInstruction != null) {
			root.set("systemInstruction", systemInstruction);
		}

		ObjectNode generationConfig = root.putObject("generationConfig");
		Object maxTokens = openAiRequest.get("max_tokens");
		if (maxTokens instanceof Number n) {
			generationConfig.put("maxOutputTokens", n.intValue());
		} else {
			generationConfig.put("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);
		}

		Object temperature = openAiRequest.get("temperature");
		if (temperature instanceof Number n) {
			generationConfig.put("temperature", n.doubleValue());
		}

		Object topP = openAiRequest.get("top_p");
		if (topP instanceof Number n) {
			generationConfig.put("topP", n.doubleValue());
		}

		root.put("project", projectId);

		try {
			return MAPPER.writeValueAsString(root);
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize Gemini payload", e);
		}
	}
}
