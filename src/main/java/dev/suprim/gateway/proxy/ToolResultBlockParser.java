package dev.suprim.gateway.proxy;

import tools.jackson.databind.JsonNode;

import java.util.List;

final class ToolResultBlockParser {

	private ToolResultBlockParser() {}

	static boolean hasToolResult(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (!item.has("type")) {
				continue;
			}

			if (!"tool_result".equals(item.get("type").stringValue())) {
				continue;
			}

			return true;
		}
		return false;
	}

	/**
	 * A turn may carry the user's own text alongside its tool results. That text is emitted
	 * as a separate user message after the results, so it still reaches the model.
	 */
	static void parse(JsonNode content, List<Message> result) {
		StringBuilder userText = new StringBuilder();
		for (JsonNode block : content) {
			String type;

			if (block.has("type")) {
				type = block.get("type").stringValue();
			} else {
				type = "";
			}

			if ("text".equals(type) && block.has("text")) {
				userText.append(block.get("text").stringValue());
			} else if ("tool_result".equals(type)) {
				String toolUseId;

				if (block.has("tool_use_id")) {
					toolUseId = block.get("tool_use_id").stringValue();
				} else {
					toolUseId = "";
				}

				result.add(
						Message.builder()
						       .role("tool")
						       .content(extractText(block))
						       .toolCallId(toolUseId)
						       .toolError(block.has("is_error") &&
						                  block.get("is_error").asBoolean())
						       .build()
				);
			}
		}
		if (!userText.isEmpty()) {
			result.add(Message.of("user", userText.toString()));
		}
	}

	private static String extractText(JsonNode block) {
		if (!block.has("content")) {
			return "";
		}
		JsonNode c = block.get("content");
		if (c.isString()) {
			return c.stringValue();
		}
		if (c.isArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode item : c) {
				if (item.has("type") && "text".equals(item.get("type")
				                                          .stringValue())
				) {
					sb.append(item.get("text").stringValue());
				}
			}
			return sb.toString();
		}
		return "";
	}
}
