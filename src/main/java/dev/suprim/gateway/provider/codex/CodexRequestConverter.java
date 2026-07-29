package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chat Completions shape → Responses API request body for Codex upstream.
 * Assistant tool_calls become function_call items; tool role becomes function_call_output.
 */
@Slf4j
final class CodexRequestConverter {

	private static final JsonMapper MAPPER = new JsonMapper();

	private CodexRequestConverter() {}

	static ObjectNode toPayload(String model, InternalRequest request) {
		ObjectNode root = toPayload(
				model,
				request.messages(),
				request.tools(),
				true,
				request.clientSessionId()
		);
		if (request.temperature() != null) {
			root.put("temperature", request.temperature());
		}
		if (request.maxTokens() != null) {
			log.warn(
					LogTag.CODEX + "Ignoring output limit {}: Codex does not support an output cap",
					request.maxTokens()
			);
		}
		mapSamplingToReasoning(root);
		logToolResults(request.messages());
		return root;
	}

	static ObjectNode toPayload(
			String model,
			List<Message> messages,
			List<Tool> tools,
			boolean stream,
			String clientSessionId
	) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", model);
		root.put("store", false);
		root.put("stream", stream);
		if (clientSessionId != null && !clientSessionId.isBlank()) {
			root.put("prompt_cache_key", clientSessionId.trim());
		}

		StringBuilder instructions = new StringBuilder();
		root.set("input", toInput(messages, instructions));
		if (!instructions.isEmpty()) {
			root.put("instructions", instructions.toString());
		}
		if (tools != null && !tools.isEmpty()) {
			root.set("tools", toTools(tools));
		}
		return root;
	}

	static ObjectNode toPayload(
			String model,
			List<Message> messages,
			List<Tool> tools,
			boolean stream
	) {
		return toPayload(model, messages, tools, stream, null);
	}

	static ArrayNode toInput(List<Message> messages) {
		return toInput(messages, new StringBuilder());
	}

	static ArrayNode toInput(
			List<Message> messages,
			StringBuilder instructions
	) {
		ArrayNode input = MAPPER.createArrayNode();
		if (messages == null) {
			return input;
		}

		for (Message msg : messages) {
			if (msg == null || msg.role() == null) {
				continue;
			}

			// Codex rejects system/developer in input — lift to top-level instructions
			if ("system".equals(msg.role()) || "developer".equals(msg.role())) {
				String text = contentAsString(msg.content());
				if (!text.isEmpty()) {
					if (!instructions.isEmpty()) {
						instructions.append("\n\n");
					}
					instructions.append(text);
				}
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
			if (tool == null || !"function".equals(tool.type()) || tool.function() == null ||
			    tool.function().name() == null || tool.function().name().isBlank()) {
				continue;
			}
			Tool.Function fn = tool.function();
			if (fn.parameters() != null && !fn.parameters().isObject()) {
				continue;
			}
			ObjectNode t = arr.addObject();
			t.put("type", "function");
			t.put("name", fn.name());
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

	/**
	 * The ChatGPT Codex backend rejects sampling and output-cap fields supported by
	 * public OpenAI APIs. Temperature is mapped to a catalog-supported reasoning
	 * effort before those fields are removed.
	 */
	private static void mapSamplingToReasoning(ObjectNode node) {
		if (!node.has("reasoning") && node.has("temperature")) {
			double temperature = node.get("temperature").asDouble(1.0);
			String effort;
			if (temperature <= 0.3) {
				effort = "high";
			} else if (temperature <= 0.7) {
				effort = "medium";
			} else {
				effort = "low";
			}
			node.putObject("reasoning").put("effort", effort);
		}
		node.remove("temperature");
		node.remove("top_p");
		node.remove("frequency_penalty");
		node.remove("presence_penalty");
		node.remove("logit_bias");
		node.remove("logprobs");
		node.remove("top_logprobs");
		node.remove("n");
		node.remove("max_tokens");
		node.remove("max_completion_tokens");
		node.remove("max_output_tokens");
		node.remove("thinking");
	}

	private static void logToolResults(List<Message> messages) {
		if (messages == null || !log.isDebugEnabled()) {
			return;
		}
		for (Message message : messages) {
			if (message != null && "tool".equals(message.role())) {
				log.debug(
						LogTag.CODEX + "Tool result: callId={} resultBytes={} error={}",
						message.toolCallId(), utf8Length(message.content()),
						Boolean.TRUE.equals(message.toolError())
				);
			}
		}
	}

	private static int utf8Length(Object value) {
		return value == null ? 0 : value.toString().getBytes(StandardCharsets.UTF_8).length;
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
