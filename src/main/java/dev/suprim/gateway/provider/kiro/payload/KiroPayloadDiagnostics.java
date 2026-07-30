package dev.suprim.gateway.provider.kiro.payload;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Emits a safe structural view of the exact Kiro request body for upstream validation errors.
 */
@Slf4j
public final class KiroPayloadDiagnostics {

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

		logSuspiciousFields(root);
	}

	public static void logInvalidRequest(
			String payload,
			String endpoint,
			String account,
			String reason,
			String message
	) {
		try {
			JsonNode parsed = MAPPER.readTree(payload);
			if (!parsed.isObject()) {
				log.warn(
						"[PayloadInvalid] endpoint={} account={} reason={} message={} payloadBytes={} fingerprint={} rootType={}",
						endpoint, account, bounded(reason), bounded(message),
						utf8Bytes(payload), fingerprint(payload), type(parsed)
				);
				return;
			}
			ObjectNode root = (ObjectNode) parsed;
			log.warn(
					"[PayloadInvalid] endpoint={} account={} reason={} message={} summary={}",
					endpoint, account, bounded(reason), bounded(message), summary(root)
			);
			logSuspiciousFields(root);
			logTools(root);
		} catch (Exception exception) {
			log.warn(
					"[PayloadInvalid] endpoint={} account={} reason={} message={} payloadBytes={} fingerprint={} parseError={}",
					endpoint, account, bounded(reason), bounded(message),
					utf8Bytes(payload), fingerprint(payload),
					exception.getClass().getSimpleName()
			);
		}
	}

	private static PayloadSummary summary(ObjectNode root) {
		String payload = root.toString();
		String systemPrompt = root.path("systemPrompt").asString();
		JsonNode history = root.at("/conversationState/history");
		JsonNode current = root.at(
				"/conversationState/currentMessage/userInputMessage"
		);
		JsonNode firstHistoryUser = firstHistoryUser(history);
		JsonNode sessionStart = firstHistoryUser.isMissingNode()
				? current
				: firstHistoryUser;
		JsonNode tools = current.at("/userInputMessageContext/tools");
		return new PayloadSummary(
				fingerprint(payload),
				utf8Bytes(payload),
				current.path("modelId").asString(),
				history.isArray() ? history.size() : 0,
				tools.isArray() ? tools.size() : 0,
				bytes(tools),
				root.has("systemPrompt"),
				systemPrompt.length(),
				utf8Bytes(systemPrompt),
				firstHistoryUser.isMissingNode() ? "current" : "history",
				sessionStart.path("content").asString().length(),
				utf8Bytes(sessionStart.path("content").asString()),
				!systemPrompt.isEmpty() && sessionStart.path("content")
				                                      .asString()
				                                      .startsWith(systemPrompt),
				current.path("content").asString().length(),
				utf8Bytes(current.path("content").asString()),
				root.has("inferenceConfig"),
				root.has("additionalModelRequestFields")
		);
	}

	private static JsonNode firstHistoryUser(JsonNode history) {
		if (history.isArray()) {
			for (JsonNode entry : history) {
				JsonNode user = entry.get("userInputMessage");
				if (user != null) {
					return user;
				}
			}
		}
		return MissingNode.getInstance();
	}

	private static String fingerprint(String payload) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(
					payload.getBytes(StandardCharsets.UTF_8)
			);
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static int utf8Bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static String bounded(String value) {
		if (value == null) {
			return "";
		}
		return value.length() <= 200 ? value : value.substring(0, 200);
	}

	private record PayloadSummary(
			String fingerprint,
			int payloadBytes,
			String model,
			int historyItems,
			int toolCount,
			int toolsBytes,
			boolean hasSystemPrompt,
			int systemPromptChars,
			int systemPromptBytes,
			String sessionStartLocation,
			int sessionStartChars,
			int sessionStartBytes,
			boolean systemPromptInSessionStart,
			int currentContentChars,
			int currentContentBytes,
			boolean hasInferenceConfig,
			boolean hasAdditionalModelRequestFields
	) {}

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
			for (Map.Entry<String, JsonNode> field : node.properties()) {
				logTree(
						field.getValue(),
						path + "/" + field.getKey(),
						depth + 1
				);
			}
			return;
		}
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				logTree(node.get(index), path + "/" + index, depth + 1);
			}
		}
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
			JsonNode schema = specification.at("/inputSchema/json");

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
		if (required == null) {
			return;
		}
		if (!required.isArray() || required.isEmpty()) {
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
