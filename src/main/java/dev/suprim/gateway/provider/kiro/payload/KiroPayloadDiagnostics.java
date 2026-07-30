package dev.suprim.gateway.provider.kiro.payload;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Emits a safe structural view of the exact Kiro request body for upstream validation errors.
 */
@Slf4j
final class KiroPayloadDiagnostics {

	private static final JsonMapper MAPPER = new JsonMapper();
	private static final Set<String> SENSITIVE_FIELDS = Set.of(
			"content",
			"bytes",
			"profileArn",
			"systemPrompt",
			"conversationId",
			"agentContinuationId"
	);
	private static final Set<String> EXPECTED_ROOT_FIELDS = Set.of(
			"conversationState",
			"agentMode",
			"systemPrompt",
			"profileArn",
			"inferenceConfig",
			"additionalModelRequestFields"
	);

	private KiroPayloadDiagnostics() {}

	static void log(ObjectNode root) {
		if (!log.isDebugEnabled()) {
			return;
		}

		ObjectNode safeBody = sanitize(root, "");
		log.debug("[PayloadDebug] body={}", safeBody);
		logTree(root, "$", 0);
		logTools(root);
		logSuspiciousFields(root);
	}

	private static ObjectNode sanitize(ObjectNode source, String path) {
		ObjectNode safe = MAPPER.createObjectNode();
		Iterator<Map.Entry<String, JsonNode>> fields = source.properties()
		                                                     .iterator();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			String childPath = path + "/" + field.getKey();
			JsonNode value = field.getValue();
			if (SENSITIVE_FIELDS.contains(field.getKey())) {
				safe.put(field.getKey(), summary(value));
			} else if (value.isObject()) {
				safe.set(
						field.getKey(),
						sanitize((ObjectNode) value, childPath)
				);
			} else if (value.isArray()) {
				safe.set(
						field.getKey(),
						sanitizeArray((ArrayNode) value, childPath)
				);
			} else {
				safe.set(field.getKey(), value.deepCopy());
			}
		}
		return safe;
	}

	private static ArrayNode sanitizeArray(ArrayNode source, String path) {
		ArrayNode safe = MAPPER.createArrayNode();
		for (int index = 0; index < source.size(); index++) {
			JsonNode value = source.get(index);
			String childPath = path + "/" + index;
			if (value.isObject()) {
				safe.add(sanitize((ObjectNode) value, childPath));
			} else if (value.isArray()) {
				safe.add(sanitizeArray((ArrayNode) value, childPath));
			} else if (value.isTextual()) {
				safe.add(summary(value));
			} else {
				safe.add(value.deepCopy());
			}
		}
		return safe;
	}

	private static void logTree(JsonNode node, String path, int depth) {
		if (depth > 12) {
			log.debug(
					"[PayloadDebug] path={} type={} warning=max-depth",
					path,
					type(node)
			);
			return;
		}
		if (node.isObject()) {
			log.debug(
					"[PayloadDebug] path={} type=object keys={} bytes={}",
					path,
					node.size(),
					bytes(node)
			);
			Iterator<Map.Entry<String, JsonNode>> fields = node.properties()
			                                                   .iterator();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				logTree(
						field.getValue(),
						path + "/" + field.getKey(),
						depth + 1
				);
			}
			return;
		}
		if (node.isArray()) {
			log.debug(
					"[PayloadDebug] path={} type=array items={} bytes={}",
					path,
					node.size(),
					bytes(node)
			);
			for (int index = 0; index < node.size(); index++) {
				logTree(node.get(index), path + "/" + index, depth + 1);
			}
			return;
		}
		log.debug(
				"[PayloadDebug] path={} type={} valueLength={} bytes={}",
				path,
				type(node),
				valueLength(node),
				bytes(node)
		);
	}

	private static void logTools(ObjectNode root) {
		JsonNode tools = root.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/tools"
		);
		if (!tools.isArray()) {
			return;
		}

		Set<String> names = new HashSet<>();
		for (int index = 0; index < tools.size(); index++) {
			JsonNode specification = tools.get(index).path("toolSpecification");
			String name = specification.path("name").asString();
			JsonNode description = specification.get("description");
			JsonNode schema = specification.at("/inputSchema/json");
			JsonNode properties = schema.get("properties");
			JsonNode required = schema.get("required");
			log.debug(
					"[PayloadDebug] tool={} name={} nameLength={} descriptionLength={} schemaType={} schemaBytes={} properties={} required={} schema={}",
					index,
					name,
					name.length(),
					valueLength(description),
					schema.path("type").asString("<missing>"),
					bytes(schema),
					properties != null &&
					properties.isObject() ? properties.size() : -1,
					required != null &&
					required.isArray() ? required.size() : -1,
					sanitizeSchema(schema)
			);
			if (!names.add(name)) {
				log.warn(
						"[PayloadDebug] suspicious=duplicate-tool-name tool={} name={}",
						index,
						name
				);
			}
			logSchemaProblems(index, schema);
		}
	}

	private static JsonNode sanitizeSchema(JsonNode schema) {
		if (!schema.isObject()) {
			return schema.deepCopy();
		}
		return sanitize((ObjectNode) schema, "/schema");
	}

	private static void logSchemaProblems(int toolIndex, JsonNode schema) {
		if (!schema.isObject()) {
			log.warn(
					"[PayloadDebug] suspicious=tool-schema-not-object tool={}",
					toolIndex
			);
			return;
		}
		if (!"object".equals(schema.path("type").asString())) {
			log.warn(
					"[PayloadDebug] suspicious=tool-schema-type tool={} type={}",
					toolIndex,
					schema.path("type").asString("<missing>")
			);
		}
		JsonNode properties = schema.get("properties");
		if (properties == null || !properties.isObject()) {
			log.warn(
					"[PayloadDebug] suspicious=tool-properties-invalid tool={}",
					toolIndex
			);
		}
		JsonNode required = schema.get("required");
		if (required == null || !required.isArray()) {
			log.warn(
					"[PayloadDebug] suspicious=tool-required-invalid tool={}",
					toolIndex
			);
			return;
		}
		for (JsonNode property : required) {
			String name = property.asString();
			if (properties == null || !properties.has(name)) {
				log.warn(
						"[PayloadDebug] suspicious=required-property-missing tool={} property={}",
						toolIndex,
						name
				);
			}
		}
	}

	private static void logSuspiciousFields(ObjectNode root) {
		Iterator<String> rootFields = root.propertyNames().iterator();
		while (rootFields.hasNext()) {
			String field = rootFields.next();
			if (!EXPECTED_ROOT_FIELDS.contains(field)) {
				log.warn(
						"[PayloadDebug] suspicious=unexpected-root-field field={}",
						field
				);
			}
		}
		JsonNode current = root.at(
				"/conversationState/currentMessage/userInputMessage");
		for (String required : Set.of("content", "modelId", "origin")) {
			if (!current.has(required)) {
				log.warn(
						"[PayloadDebug] suspicious=current-field-missing field={}",
						required
				);
			}
		}
		JsonNode inference = root.get("inferenceConfig");
		boolean thinkingEnabled = root.path("systemPrompt")
		                              .asString()
		                              .contains("<thinking_mode>") ||
		                          root.has("additionalModelRequestFields");
		if (thinkingEnabled && inference != null &&
		    (inference.has("temperature") || inference.has("topP"))) {
			log.warn(
					"[PayloadDebug] suspicious=thinking-with-sampling inference={}",
					inference
			);
		}
	}

	private static String summary(JsonNode value) {
		return "<redacted type=" + type(value) +
		       " length=" + valueLength(value) +
		       " bytes=" + bytes(value) + ">";
	}

	private static int valueLength(JsonNode value) {
		if (value == null || value.isNull()) {
			return 0;
		}
		return value.isString() ? value.asString().length() : value.toString()
		                                                           .length();
	}

	private static int bytes(JsonNode value) {
		if (value == null) {
			return 0;
		}
		String text = value.isString() ? value.asString() : value.toString();
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

	private static String type(JsonNode value) {
		return value == null ? "missing" : value.getNodeType()
		                                        .name()
		                                        .toLowerCase();
	}
}
