package dev.suprim.gateway.provider.antigravity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders a request payload with its bulk elided, so a rejected request can be
 * inspected in a log line.
 * <p>
 * The upstream answers a malformed request with a bare "Request contains an
 * invalid argument" that names no field, so the only way to tell which part it
 * objected to is to look at the shape actually sent. Long strings — base64 media,
 * prompts, tool schemas — are replaced by their length: the structure is the
 * diagnostic, the content is not, and a megabyte of base64 in the log would bury it.
 */
final class AntigravityPayloadShape {

	private static final JsonMapper MAPPER = new JsonMapper();
	private static final int MAX_STRING = 80;

	private AntigravityPayloadShape() {}

	/**
	 * @return {@code payload} with long strings elided, or the raw payload's length
	 * if it does not parse
	 */
	static String of(String payload) {
		if (payload == null) {
			return "<none>";
		}
		try {
			return elide(MAPPER.readTree(payload)).toString();
		} catch (Exception e) {
			return "<unparseable, " + payload.length() + " chars>";
		}
	}

	private static JsonNode elide(JsonNode node) {
		if (node.isObject()) {
			ObjectNode out = MAPPER.createObjectNode();
			for (String field : node.propertyNames()) {
				out.set(field, elide(node.get(field)));
			}
			return out;
		}
		if (node.isArray()) {
			ArrayNode out = MAPPER.createArrayNode();
			for (JsonNode item : node) {
				out.add(elide(item));
			}
			return out;
		}
		if (node.isString() && node.stringValue().length() > MAX_STRING) {
			return MAPPER.getNodeFactory()
			             .stringNode(
					             "<" + node.stringValue().length() + " chars>"
			             );
		}
		return node;
	}
}
