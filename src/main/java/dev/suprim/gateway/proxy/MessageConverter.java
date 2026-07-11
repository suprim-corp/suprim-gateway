package dev.suprim.gateway.proxy;

import dev.suprim.gateway.api.request.MessagesRequest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

public final class MessageConverter {

	private static final JsonMapper MAPPER = new JsonMapper();

	private MessageConverter() {}

	public static List<Message> fromAnthropic(MessagesRequest request) {
		List<Message> result = new ArrayList<>();

		JsonNode sys = request.system();
		if (sys != null) {
			result.add(Message.of("system", extractSystemText(sys)));
		}

		if (request.messages() != null) {
			for (MessagesRequest.Message msg : request.messages()) {
				convertMessage(msg, result);
			}
		}

		return result;
	}

	public static List<Message> fromResponses(Object input) {
		return ResponsesInputConverter.convert(input);
	}

	private static void convertMessage(
			MessagesRequest.Message msg,
			List<Message> result
	) {
		String role = msg.role();
		JsonNode content = msg.content();

		if (content == null) {
			result.add(Message.of(role, ""));
		} else if (content.isString()) {
			result.add(Message.of(role, content.stringValue()));
		} else if (content.isArray() && "assistant".equals(role) &&
		           AssistantBlockParser.hasToolUse(content)) {
			result.add(AssistantBlockParser.parse(content));
		} else if (content.isArray() && "user".equals(role) &&
		           ToolResultBlockParser.hasToolResult(content)) {
			ToolResultBlockParser.parse(content, result);
		} else if (content.isArray() && hasImageBlock(content)) {
			List<Object> parts = MAPPER.convertValue(
					content,
					new TypeReference<>() {}
			);
			result.add(Message.of(role, parts));
		} else if (content.isArray()) {
			result.add(Message.of(role, extractTextBlocks(content)));
		} else {
			result.add(Message.of(role, content.toString()));
		}
	}

	private static String extractSystemText(JsonNode sys) {
		if (sys.isString()) return sys.stringValue();
		if (sys.isArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode item : sys) {
				if (item.has("text")) sb.append(item.get("text").stringValue());
			}
			return sb.toString();
		}
		return sys.toString();
	}

	private static String extractTextBlocks(JsonNode contentArray) {
		StringBuilder sb = new StringBuilder();
		for (JsonNode item : contentArray) {
			if (item.has("type") && "text".equals(item.get("type")
			                                          .stringValue()))
				sb.append(item.get("text").stringValue());
		}
		return sb.toString();
	}

	private static boolean hasImageBlock(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "image".equals(item.get("type")
			                                           .stringValue()))
				return true;
		}
		return false;
	}
}
