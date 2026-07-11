package dev.suprim.gateway.proxy;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

final class AssistantBlockParser {

	private AssistantBlockParser() {}

	static boolean hasToolUse(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "tool_use".equals(item.get("type")
			                                              .stringValue()))
				return true;
		}
		return false;
	}

	static Message parse(JsonNode content) {
		StringBuilder text = new StringBuilder();
		List<Message.ToolCall> toolCalls = new ArrayList<>();

		for (JsonNode block : content) {
			String type = block.has("type") ? block.get("type")
			                                       .stringValue() : "";
			if ("text".equals(type) && block.has("text")) {
				text.append(block.get("text").stringValue());
			} else if ("tool_use".equals(type)) {
				String id = block.has("id") ? block.get("id")
				                                   .stringValue() : "";
				String name = block.has("name") ? block.get("name")
				                                       .stringValue() : "";
				String arguments = block.has("input") ? block.get("input")
				                                             .toString() : "{}";
				toolCalls.add(
						Message.ToolCall.builder()
						                .id(id)
						                .type("function")
						                .function(
								                Message.Function.builder()
								                                .name(name)
								                                .arguments(
										                                arguments
								                                )
								                                .build()
						                )
						                .build()
				);
			}
		}

		return Message.builder()
		              .role("assistant")
		              .content(text.toString())
		              .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
		              .build();
	}
}
