package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class AntigravityPayloadBuilder {

	private static final JsonMapper MAPPER = new JsonMapper();
	private static final int DEFAULT_MAX_OUTPUT_TOKENS = 65536;

	static String build(
			InternalRequest request,
			String model,
			String projectId
	) {
		return build(request, model, projectId, Map.of());
	}

	static String build(
			InternalRequest request,
			String model,
			String projectId,
			Map<String, String> thoughtSignatures
	) {
		List<Message> messages = request.messages() != null
				? request.messages()
				: List.of();

		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", model);
		root.put("project", projectId);
		root.put("userAgent", "antigravity");

		ObjectNode reqNode = root.putObject("request");
		ArrayNode contents = reqNode.putArray("contents");
		ObjectNode systemInstruction = null;

		for (Message msg : messages) {
			String role = msg.role();

			if ("system".equals(role)) {
				String text = Optional.ofNullable(msg.content())
				                      .map(Object::toString)
				                      .orElse("");
				systemInstruction = MAPPER.createObjectNode();
				ArrayNode parts = systemInstruction.putArray("parts");
				parts.addObject().put("text", text);
				continue;
			}

			if ("assistant".equals(role) && msg.toolCalls() != null &&
			    !msg.toolCalls().isEmpty()) {
				ObjectNode entry = contents.addObject();
				entry.put("role", "model");
				ArrayNode parts = entry.putArray("parts");
				String text = Optional.ofNullable(msg.content())
				                      .map(Object::toString)
				                      .orElse("");
				if (!text.isEmpty()) {
					parts.addObject().put("text", text);
				}
				for (Message.ToolCall tc : msg.toolCalls()) {
					if (tc.function() == null) {
						continue;
					}
					ObjectNode fcPart = parts.addObject();
					ObjectNode fcNode = fcPart.putObject("functionCall");
					fcNode.put("name", tc.function().name());
					String args = tc.function().arguments();
					if (args != null && !args.isEmpty()) {
						fcNode.set("args", MAPPER.readTree(args));
					} else {
						fcNode.putObject("args");
					}
					String sig = tc.id() !=
					             null ? thoughtSignatures.get(tc.id()) : null;
					if (sig != null) {
						fcPart.put("thoughtSignature", sig);
					}
				}
				continue;
			}

			if ("tool".equals(role) && msg.toolCallId() != null) {
				ObjectNode entry = contents.addObject();
				entry.put("role", "user");
				ArrayNode parts = entry.putArray("parts");
				ObjectNode frNode = parts.addObject().putObject(
						"functionResponse");
				frNode.put(
						"name",
						msg.name() != null ? msg.name() : msg.toolCallId()
				);
				String content = Optional.ofNullable(msg.content())
				                         .map(Object::toString)
				                         .orElse("{}");
				try {
					frNode.set("response", MAPPER.readTree(content));
				} catch (Exception e) {
					ObjectNode wrapper = MAPPER.createObjectNode();
					wrapper.put("result", content);
					frNode.set("response", wrapper);
				}
				continue;
			}

			String geminiRole = "assistant".equals(role) ? "model" : role;
			ObjectNode entry = contents.addObject();
			entry.put("role", geminiRole);
			ArrayNode parts = entry.putArray("parts");
			String text = Optional.ofNullable(msg.content())
			                      .map(Object::toString)
			                      .orElse("");
			parts.addObject().put("text", text);
		}

		if (systemInstruction != null) {
			reqNode.set("systemInstruction", systemInstruction);
		}

		if (request.tools() != null && !request.tools().isEmpty()) {
			ArrayNode toolsArray = reqNode.putArray("tools");
			ObjectNode toolObj = toolsArray.addObject();
			ArrayNode declarations = toolObj.putArray("functionDeclarations");
			for (Tool tool : request.tools()) {
				if (tool.function() == null) {
					continue;
				}
				ObjectNode decl = declarations.addObject();
				decl.put("name", tool.function().name());
				if (tool.function().description() != null) {
					decl.put("description", tool.function().description());
				}
				JsonNode params = tool.function().parameters();
				if (params != null) {
					decl.set(
							"parameters",
							stripUnsupportedFields(params.deepCopy())
					);
				}
			}
		}

		ObjectNode generationConfig = reqNode.putObject("generationConfig");
		if (request.maxTokens() != null) {
			generationConfig.put("maxOutputTokens", request.maxTokens());
		} else {
			generationConfig.put("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);
		}

		if (request.temperature() != null) {
			generationConfig.put("temperature", request.temperature());
		}

		try {
			return MAPPER.writeValueAsString(root);
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize Gemini payload", e);
		}
	}

	private static final Set<String> SUPPORTED_SCHEMA_FIELDS = Set.of(
			"type", "title", "description",
			"properties", "required", "additionalProperties",
			"enum", "format",
			"minimum", "maximum",
			"items", "prefixItems", "minItems", "maxItems",
			"nullable"
	);

	private static JsonNode stripUnsupportedFields(JsonNode node) {
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			if (obj.has("type") || obj.has("properties") || obj.has("items")) {
				List<String> toRemove = new ArrayList<>();
				for (String fieldName : obj.propertyNames()) {
					if (!SUPPORTED_SCHEMA_FIELDS.contains(fieldName)) {
						toRemove.add(fieldName);
					}
				}
				if (!toRemove.isEmpty()) {
					log.debug(
							"[Antigravity] Stripping unsupported schema fields: {}",
							toRemove
					);
					for (String field : toRemove) {
						obj.remove(field);
					}
				}
			}
			for (String fieldName : List.copyOf(obj.propertyNames())) {
				JsonNode child = obj.get(fieldName);
				if (child != null && (child.isObject() || child.isArray())) {
					stripUnsupportedFields(child);
				}
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				stripUnsupportedFields(child);
			}
		}
		return node;
	}
}
