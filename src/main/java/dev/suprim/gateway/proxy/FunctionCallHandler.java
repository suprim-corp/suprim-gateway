package dev.suprim.gateway.proxy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FunctionCallHandler {

	private FunctionCallHandler() {}

	static Message.ToolCall toToolCall(Map<?, ?> m) {
		String callId = Optional.ofNullable(m.get("call_id"))
		                        .map(Object::toString)
		                        .orElse("");
		String name = Optional.ofNullable(m.get("name"))
		                      .map(Object::toString)
		                      .orElse("");
		String arguments = Optional.ofNullable(m.get("arguments"))
		                           .map(Object::toString)
		                           .orElse("{}");
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
