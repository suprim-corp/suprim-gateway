package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class AntigravityPayloadBuilder {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int DEFAULT_MAX_OUTPUT_TOKENS = 65536;

	static String build(Object openAiRequest, String projectId) {
		JsonNode requestNode = MAPPER.valueToTree(openAiRequest);
		JsonNode messagesNode = requestNode.get("messages");

		List<Message> messages = new ArrayList<>();
		if (messagesNode != null && messagesNode.isArray()) {
			for (JsonNode node : messagesNode) {
				Message msg = MAPPER.treeToValue(node, Message.class);
				messages.add(msg);
			}
		}

		ObjectNode root = MAPPER.createObjectNode();
		ArrayNode contents = root.putArray("contents");
		ObjectNode systemInstruction = null;

		for (Message msg : messages) {
			String role = msg.role();
			String content = Optional.ofNullable(msg.content())
			                         .map(Object::toString)
			                         .orElse("");

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
		JsonNode maxTokens = requestNode.get("max_tokens");
		if (maxTokens != null && maxTokens.isNumber()) {
			generationConfig.put("maxOutputTokens", maxTokens.asInt());
		} else {
			generationConfig.put("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);
		}

		JsonNode temperature = requestNode.get("temperature");
		if (temperature != null && temperature.isNumber()) {
			generationConfig.put("temperature", temperature.asDouble());
		}

		JsonNode topP = requestNode.get("top_p");
		if (topP != null && topP.isNumber()) {
			generationConfig.put("topP", topP.asDouble());
		}

		root.put("project", projectId);

		try {
			return MAPPER.writeValueAsString(root);
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize Gemini payload", e);
		}
	}
}
