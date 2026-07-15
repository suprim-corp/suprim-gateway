package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts InternalRequest to DeepSeek Web API JSON payload.
 */
public final class DeepSeekRequestConverter {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Map<String, String> MODEL_TYPE_MAP = Map.of(
			"v4-flash", "default",
			"v4-pro", "default",
			"v4-flash-search", "default",
			"v4-pro-search", "default",
			"v4-vision", "vision",
			"v4-flash-think", "default",
			"v4-pro-think", "default"
	);

	private DeepSeekRequestConverter() {}

	public static String convert(
			InternalRequest request,
			String chatSessionId
	) {
		String model = stripPrefix(request.model());
		boolean searchEnabled = model.endsWith("-search");
		String modelType = MODEL_TYPE_MAP.getOrDefault(model, "default");

		ObjectNode node = JSON.createObjectNode();
		node.put("chat_session_id", chatSessionId);
		node.put("prompt", flattenMessages(request.messages()));
		node.putNull("parent_message_id");
		node.put("model_type", modelType);
		node.putPOJO("ref_file_ids", List.of());
		node.put("thinking_enabled", true);
		node.put("search_enabled", searchEnabled);
		node.put(
				"temperature",
				Optional.ofNullable(request.temperature()).orElse(0.7)
		);

		return node.toString();
	}

	private static String stripPrefix(String model) {
		if (model.startsWith("deepseek/")) {
			return model.substring("deepseek/".length());
		}
		return model;
	}

	private static String flattenMessages(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return "";
		}
		if (messages.size() == 1) {
			return extractText(messages.getFirst());
		}
		StringBuilder sb = new StringBuilder();
		for (Message msg : messages) {
			String text = extractText(msg);
			if (text.isEmpty()) continue;
			sb.append("[").append(msg.role()).append("]: ").append(text).append(
					"\n\n");
		}
		return sb.toString().trim();
	}

	private static String extractText(Message msg) {
		if (msg.content() == null) {
			return "";
		}
		if (msg.content() instanceof String text) {
			return text;
		}
		return msg.content().toString();
	}
}
