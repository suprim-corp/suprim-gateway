package dev.suprim.gateway.proxy;

import tools.jackson.databind.JsonNode;

import java.util.List;

final class ToolResultBlockParser {

	private ToolResultBlockParser() {}

	static boolean hasToolResult(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "tool_result".equals(item.get("type")
			                                                 .stringValue()))
				return true;
		}
		return false;
	}

	static void parse(JsonNode content, List<Message> result) {
		for (JsonNode block : content) {
			String type = block.has("type") ? block.get("type")
			                                       .stringValue() : "";
			if ("tool_result".equals(type)) {
				String toolUseId = block.has("tool_use_id") ? block.get(
						"tool_use_id").stringValue() : "";
				String text = extractText(block);
				result.add(
						Message.builder()
						       .role("tool")
						       .content(text)
						       .toolCallId(toolUseId)
						       .build()
				);
			}
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
