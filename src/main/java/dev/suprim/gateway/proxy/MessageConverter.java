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
