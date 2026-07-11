package dev.suprim.gateway.proxy;

import dev.suprim.gateway.api.request.CompletionsRequest;
import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.api.request.ResponsesRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class ToolMapper {

	private static final JsonMapper MAPPER = new JsonMapper();
	private static final JsonNode EMPTY_PARAMS = emptyObjectSchema();

	private ToolMapper() {}

	private static ObjectNode emptyObjectSchema() {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", "object");
		node.putObject("properties");
		return node;
	}

	private static JsonNode ensureParams(JsonNode parameters) {
		return parameters != null ? parameters : EMPTY_PARAMS;
	}

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
							.parameters(ensureParams(fn.parameters()))
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
							.parameters(ensureParams(tool.inputSchema()))
							.build())
					.build());
		}
		return result;
	}

	public static List<Tool> fromResponses(List<ResponsesRequest.Tool> tools) {
		if (tools == null || tools.isEmpty()) return List.of();
		List<Tool> result = new ArrayList<>(tools.size());
		for (ResponsesRequest.Tool tool : tools) {
			if (!"function".equals(tool.type())) {
				log.debug("Skipping non-function tool: {}", tool);
				continue;
			}
			result.add(Tool.builder()
					.type("function")
					.function(Tool.Function.builder()
							.name(tool.name())
							.description(tool.description())
							.parameters(ensureParams(tool.parameters()))
							.strict(tool.strict())
							.build())
					.build());
		}
		return result;
	}
}
