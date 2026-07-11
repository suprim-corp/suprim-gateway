package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

class AntigravityPayloadBuilder {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int DEFAULT_MAX_OUTPUT_TOKENS = 65536;

	static String build(InternalRequest request, String projectId) {
		List<Message> messages = request.messages() != null
				? request.messages()
				: List.of();

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
		if (request.maxTokens() != null) {
			generationConfig.put("maxOutputTokens", request.maxTokens());
		} else {
			generationConfig.put("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);
		}

		if (request.temperature() != null) {
			generationConfig.put("temperature", request.temperature());
		}

		root.put("project", projectId);

		try {
			return MAPPER.writeValueAsString(root);
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize Gemini payload", e);
		}
	}
}
