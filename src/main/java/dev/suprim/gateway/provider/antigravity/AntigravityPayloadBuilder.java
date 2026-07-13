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

import static java.util.Objects.nonNull;

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
				boolean allHaveSignatures = msg.toolCalls().stream()
				                               .filter(
						                               tc -> nonNull(tc.function())
				                               )
				                               .allMatch(tc ->
						                               nonNull(tc.id()) &&
						                               thoughtSignatures.containsKey(
								                               tc.id()
						                               )
				                               );
				if (allHaveSignatures) {
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
						fcPart.put(
								"thoughtSignature",
								thoughtSignatures.get(tc.id())
						);
					}
				} else {
					StringBuilder summary = new StringBuilder();
					if (!text.isEmpty()) {
						summary.append(text).append("\n");
					}
					for (Message.ToolCall tc : msg.toolCalls()) {
						if (tc.function() == null) continue;
						summary.append("[Called ")
						       .append(tc.function().name())
						       .append("]\n");
					}
					parts.removeAll();
					parts.addObject().put("text", summary.toString().trim());
				}
				continue;
			}

			if ("tool".equals(role) && msg.toolCallId() != null) {
				String sig = thoughtSignatures.get(msg.toolCallId());
				ObjectNode entry = contents.addObject();
				entry.put("role", "user");
				ArrayNode parts = entry.putArray("parts");
				if (sig != null) {
					ObjectNode frNode = parts.addObject().putObject(
							"functionResponse");
					frNode.put(
							"name",
							Optional.ofNullable(msg.name())
							        .orElse(msg.toolCallId())
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
				} else {
					String content = Optional.ofNullable(msg.content())
					                         .map(Object::toString)
					                         .orElse("");
					String toolName = Optional.ofNullable(msg.name())
					                          .orElse(msg.toolCallId());
					String summary = "[Result of " + toolName + "]: " +
					                 (content.length() > 500 ?
							                  content.substring(0, 500) +
							                  "..." : content);
					parts.addObject().put("text", summary);
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

	/**
	 * Allowlist of JSON Schema fields accepted by the Antigravity (cloudcode-pa) API.
	 * Fields outside this set cause 400 errors and must be stripped before sending.
	 *
	 * @see <a href="https://github.com/NoeFabris/opencode-antigravity-auth/blob/main/docs/ANTIGRAVITY_API_SPEC.md">Antigravity API Spec</a>
	 */
	private static final Set<String> SUPPORTED_SCHEMA_FIELDS = Set.of(
			"type", "title", "description",
			"properties", "required", "additionalProperties",
			"enum", "format",
			"minimum", "maximum",
			"items", "prefixItems", "minItems", "maxItems",
			"nullable",
			"anyOf", "allOf", "oneOf"
	);

	private static JsonNode stripUnsupportedFields(JsonNode node) {
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			if (isSchemaNode(obj)) {
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

	/**
	 * Determines if a JSON object node is a schema definition (strip-eligible)
	 * vs a properties map (which just maps names to child schemas).
	 * A properties map like {@code {element: {type:string}, target: {type:string}}}
	 * must NOT be treated as a schema node even if one property is named "type".
	 */
	private static boolean isSchemaNode(ObjectNode obj) {
		JsonNode typeNode = obj.get("type");
		if (typeNode != null && typeNode.isString()) {
			return true;
		}
		if (obj.has("items") && !obj.has("type")) {
			JsonNode itemsNode = obj.get("items");
			return itemsNode != null && itemsNode.isObject();
		}
		return false;
	}
}
