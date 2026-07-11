package dev.suprim.gateway.proxy;

import dev.suprim.gateway.api.request.MessagesRequest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class MessageConverter {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MessageConverter() {}

	public static List<Message> fromAnthropic(MessagesRequest request) {
		List<Message> result = new ArrayList<>();

		JsonNode sys = request.system();
		if (sys != null) {
			String systemText;
			if (sys.isString()) {
				systemText = sys.stringValue();
			} else if (sys.isArray()) {
				StringBuilder sb = new StringBuilder();
				for (JsonNode item : sys) {
					if (item.has("text")) sb.append(item.get("text")
					                                    .stringValue());
				}
				systemText = sb.toString();
			} else {
				systemText = sys.toString();
			}
			result.add(Message.of("system", systemText));
		}

		if (request.messages() != null) {
			for (MessagesRequest.Message msg : request.messages()) {
				String role = msg.role();
				JsonNode content = msg.content();
				if (content == null) {
					result.add(Message.of(role, ""));
				} else if (content.isString()) {
					result.add(Message.of(role, content.stringValue()));
				} else if (content.isArray() && "assistant".equals(role) && hasToolUseBlock(content)) {
					result.add(buildAssistantWithToolUse(content));
				} else if (content.isArray() && "user".equals(role) && hasToolResultBlock(content)) {
					buildToolResultMessages(content, result);
				} else if (content.isArray() && hasImageBlock(content)) {
					List<Object> parts = MAPPER.convertValue(
							content,
							new TypeReference<>() {}
					);
					result.add(Message.of(role, parts));
				} else if (content.isArray()) {
					StringBuilder sb = new StringBuilder();
					for (JsonNode item : content) {
						if (item.has("type") && "text".equals(item.get("type")
						                                          .stringValue()))
							sb.append(item.get("text").stringValue());
					}
					result.add(Message.of(role, sb.toString()));
				} else {
					result.add(Message.of(role, content.toString()));
				}
			}
		}

		return result;
	}

	public static List<Message> fromResponses(Object input) {
		return ResponsesInputConverter.convert(input);
	}

	private static boolean hasToolResultBlock(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "tool_result".equals(item.get("type").stringValue()))
				return true;
		}
		return false;
	}

	private static void buildToolResultMessages(JsonNode content, List<Message> result) {
		for (JsonNode block : content) {
			String type = block.has("type") ? block.get("type").stringValue() : "";
			if ("tool_result".equals(type)) {
				String toolUseId = block.has("tool_use_id") ? block.get("tool_use_id").stringValue() : "";
				String text = "";
				if (block.has("content")) {
					JsonNode c = block.get("content");
					if (c.isString()) {
						text = c.stringValue();
					} else if (c.isArray()) {
						StringBuilder sb = new StringBuilder();
						for (JsonNode item : c) {
							if (item.has("type") && "text".equals(item.get("type").stringValue()))
								sb.append(item.get("text").stringValue());
						}
						text = sb.toString();
					}
				}
				result.add(Message.builder()
						.role("tool")
						.content(text)
						.toolCallId(toolUseId)
						.build());
			}
		}
	}

	private static boolean hasToolUseBlock(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "tool_use".equals(item.get("type").stringValue()))
				return true;
		}
		return false;
	}

	private static Message buildAssistantWithToolUse(JsonNode content) {
		StringBuilder text = new StringBuilder();
		List<Message.ToolCall> toolCalls = new ArrayList<>();

		for (JsonNode block : content) {
			String type = block.has("type") ? block.get("type").stringValue() : "";
			if ("text".equals(type) && block.has("text")) {
				text.append(block.get("text").stringValue());
			} else if ("tool_use".equals(type)) {
				String id = block.has("id") ? block.get("id").stringValue() : "";
				String name = block.has("name") ? block.get("name").stringValue() : "";
				String arguments = block.has("input") ? block.get("input").toString() : "{}";
				toolCalls.add(Message.ToolCall.builder()
						.id(id)
						.type("function")
						.function(Message.Function.builder()
								.name(name)
								.arguments(arguments)
								.build())
						.build());
			}
		}

		return Message.builder()
				.role("assistant")
				.content(text.toString())
				.toolCalls(toolCalls.isEmpty() ? null : toolCalls)
				.build();
	}

	private static boolean hasImageBlock(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "image".equals(
					item.get("type")
					    .stringValue())
			) {
				return true;
			}
		}
		return false;
	}
}
