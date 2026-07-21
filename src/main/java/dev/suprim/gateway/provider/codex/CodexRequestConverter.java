package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chat Completions shape → Responses API request body for Codex upstream.
 * Assistant tool_calls become function_call items; tool role becomes function_call_output.
 */
final class CodexRequestConverter {

	private static final JsonMapper MAPPER = new JsonMapper();

	private CodexRequestConverter() {}

	static ObjectNode toPayload(
			String model,
			List<Message> messages,
			List<Tool> tools,
			boolean stream
	) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", model);
		root.put("store", false);
		root.put("stream", stream);
		root.set("input", toInput(messages));
		if (tools != null && !tools.isEmpty()) {
			root.set("tools", toTools(tools));
		}
		return root;
	}

	static ArrayNode toInput(List<Message> messages) {
		ArrayNode input = MAPPER.createArrayNode();
		if (messages == null) {
			return input;
		}

		for (Message msg : messages) {
			if (msg == null || msg.role() == null) {
				continue;
			}

			if ("tool".equals(msg.role())) {
				ObjectNode out = input.addObject();
				out.put("type", "function_call_output");
				out.put(
						"call_id",
						Optional.ofNullable(msg.toolCallId()).orElse("")
				);
				out.put("output", contentAsString(msg.content()));
				continue;
			}

			if ("assistant".equals(msg.role()) && msg.toolCalls() != null &&
			    !msg.toolCalls().isEmpty()) {
				String text = contentAsString(msg.content());
				if (!text.isEmpty()) {
					addMessageItem(input, "assistant", text);
				}
				for (Message.ToolCall tc : msg.toolCalls()) {
					if (tc == null) {
						continue;
					}
					ObjectNode fc = input.addObject();
					fc.put("type", "function_call");
					String callId = Optional.ofNullable(tc.id()).orElse("");
					fc.put("call_id", callId);
					Optional.ofNullable(tc.id())
					        .ifPresent(id -> fc.put("id", "fc_" + id));
					Message.Function fn = tc.function();
					fc.put(
							"name",
							Optional.ofNullable(fn)
							        .map(Message.Function::name)
							        .orElse("")
					);

					fc.put(
							"arguments",
							Optional.ofNullable(fn)
							        .map(Message.Function::arguments)
							        .filter(a -> !a.isEmpty())
							        .orElse("{}")
					);
				}
				continue;
			}

			addMessageItem(input, msg.role(), contentAsString(msg.content()));
		}
		return input;
	}

	static ArrayNode toTools(List<Tool> tools) {
		ArrayNode arr = MAPPER.createArrayNode();
		for (Tool tool : tools) {
			if (tool == null || tool.function() == null) {
				continue;
			}
			Tool.Function fn = tool.function();
			ObjectNode t = arr.addObject();
			t.put("type", "function");
			Optional.ofNullable(fn.name()).ifPresent(v -> t.put("name", v));
			Optional.ofNullable(fn.description())
			        .ifPresent(v -> t.put("description", v));
			Optional.ofNullable(fn.parameters())
			        .ifPresent(v -> t.set("parameters", v));
			Optional.ofNullable(fn.strict()).ifPresent(v -> t.put("strict", v));
		}
		return arr;
	}

	private static void addMessageItem(
			ArrayNode input,
			String role,
			String text
	) {
		ObjectNode item = input.addObject();
		item.put("type", "message");
		item.put("role", role);
		item.put("content", Optional.ofNullable(text).orElse(""));
	}

	private static String contentAsString(Object content) {
		return switch (content) {
			case null -> "";
			case String s -> s;
			case JsonNode node -> {
				if (node.isString()) {
					yield node.asString();
				}
				if (node.isArray()) {
					StringBuilder sb = new StringBuilder();
					for (JsonNode part : node) {
						if (part.has("text")) {
							sb.append(part.get("text").asString());
						} else if (part.isString()) {
							sb.append(part.asString());
						}
					}
					yield sb.toString();
				}
				yield node.toString();
			}
			case List<?> parts -> {
				StringBuilder sb = new StringBuilder();
				for (Object part : parts) {
					if (part instanceof String s) {
						sb.append(s);
					} else if (part instanceof Map<?, ?> m) {
						Optional.ofNullable(m.get("text"))
						        .ifPresent(sb::append);
					}
				}
				yield sb.toString();
			}
			default -> content.toString();
		};
	}
}
