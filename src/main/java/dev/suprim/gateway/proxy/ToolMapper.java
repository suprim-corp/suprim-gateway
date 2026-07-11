package dev.suprim.gateway.proxy;

import dev.suprim.gateway.api.request.CompletionsRequest;
import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.api.request.ResponsesRequest;

import java.util.ArrayList;
import java.util.List;

public final class ToolMapper {

	private ToolMapper() {}

	public static List<Tool> fromCompletions(List<CompletionsRequest.Tool> tools) {
		if (tools == null || tools.isEmpty()) return List.of();
		List<Tool> result = new ArrayList<>(tools.size());
		for (CompletionsRequest.Tool tool : tools) {
			CompletionsRequest.FunctionDef fn = tool.function();
			result.add(Tool.builder()
					.type(tool.type())
					.function(fn == null ? null : Tool.Function.builder()
							.name(fn.name())
							.description(fn.description())
							.parameters(fn.parameters())
							.strict(fn.strict())
							.build())
					.build());
		}
		return result;
	}

	public static List<Tool> fromAnthropic(List<MessagesRequest.Tool> tools) {
		if (tools == null || tools.isEmpty()) return List.of();
		List<Tool> result = new ArrayList<>(tools.size());
		for (MessagesRequest.Tool tool : tools) {
			result.add(Tool.builder()
					.type("function")
					.function(Tool.Function.builder()
							.name(tool.name())
							.description(tool.description())
							.parameters(tool.inputSchema())
							.build())
					.build());
		}
		return result;
	}

	public static List<Tool> fromResponses(List<ResponsesRequest.Tool> tools) {
		if (tools == null || tools.isEmpty()) return List.of();
		List<Tool> result = new ArrayList<>(tools.size());
		for (ResponsesRequest.Tool tool : tools) {
			result.add(Tool.builder()
					.type("function")
					.function(Tool.Function.builder()
							.name(tool.name())
							.description(tool.description())
							.parameters(tool.parameters())
							.strict(tool.strict())
							.build())
					.build());
		}
		return result;
	}
}
