package dev.suprim.gateway.proxy;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FunctionCallHandler {

	private static final JsonMapper MAPPER = new JsonMapper();

	private FunctionCallHandler() {}

	static Message.ToolCall toToolCall(Map<?, ?> m) {
		String callId = Optional.ofNullable(m.get("call_id"))
		                        .map(Object::toString)
		                        .orElse("");
		String name = Optional.ofNullable(m.get("name"))
		                      .map(Object::toString)
		                      .orElse("");
		Object argsObj = m.get("arguments");
		String arguments = resolveArguments(argsObj);
		return Message.ToolCall.builder()
		                       .id(callId)
		                       .type("function")
		                       .function(
				                       Message.Function.builder()
				                                       .name(name)
				                                       .arguments(arguments)
				                                       .build()
		                       )
		                       .build();
	}

	private static String resolveArguments(Object argsObj) {
		if (argsObj == null) return "{}";
		if (argsObj instanceof String s) return s.isEmpty() ? "{}" : s;
		try {
			return MAPPER.writeValueAsString(argsObj);
		} catch (Exception e) {
			return "{}";
		}
	}

	static Message toToolResult(Map<?, ?> m) {
		String content = Optional.ofNullable(m.get("output"))
		                         .map(Object::toString)
		                         .orElse("");
		String callId = Optional.ofNullable(m.get("call_id"))
		                        .map(Object::toString)
		                        .orElse("");
		return Message.builder()
		              .role("tool")
		              .content(content)
		              .toolCallId(callId)
		              .build();
	}

	static Message toAssistantWithTools(
			String content,
			List<Message.ToolCall> toolCalls
	) {
		return Message.builder()
		              .role("assistant")
		              .content(content)
		              .toolCalls(toolCalls)
		              .build();
	}
}
