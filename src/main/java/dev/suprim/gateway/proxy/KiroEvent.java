package dev.suprim.gateway.proxy;

import lombok.Builder;

@Builder
public record KiroEvent(
		String type, String content, String toolName, String toolInput,
		String toolUseId, boolean toolStop
) {

	public static KiroEvent content(String text) {
		return KiroEvent.builder().type("content").content(text).build();
	}

	public static KiroEvent reasoning(String text) {
		return KiroEvent.builder().type("reasoning").content(text).build();
	}

	public static KiroEvent toolUse(String name, String input, String id) {
		return KiroEvent.builder()
		                .type("tool_use")
		                .toolName(name)
		                .toolInput(input)
		                .toolUseId(id)
		                .toolStop(true)
		                .build();
	}
}
