package dev.suprim.gateway.proxy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
		String role,
		Object content,
		String name,
		@JsonProperty("tool_calls") List<ToolCall> toolCalls,
		@JsonProperty("tool_call_id") String toolCallId
) {

	public static Message of(String role, String content) {
		return Message.builder().role(role).content(content).build();
	}

	public static Message of(String role, Object content) {
		return Message.builder().role(role).content(content).build();
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCall(
			String id,
			String type,
			Function function
	) {}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Function(
			String name,
			String arguments
	) {}
}
