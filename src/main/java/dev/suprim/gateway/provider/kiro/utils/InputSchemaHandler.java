package dev.suprim.gateway.provider.kiro.utils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class InputSchemaHandler {
	private static final JsonMapper MAPPER = new JsonMapper();

	/**
	 * Numeric bounds the upstream parses as a signed 32-bit integer. Clients emit
	 * {@code 2^53-1} here — the JavaScript safe-integer ceiling — which overflows that
	 * and makes the whole request come back as {@code REQUEST_BODY_INVALID}.
	 */
	private static final List<String> NUMERIC_BOUNDS = List.of(
			"minimum",
			"maximum",
			"exclusiveMinimum",
			"exclusiveMaximum"
	);

	/**
	 * Any one of these resolves a schema node's type. A node carrying none of them is
	 * what the upstream rejects, so {@link #clean} supplies one.
	 */
	private static final List<String> TYPE_INDICATORS = List.of(
			"type",
			"anyOf",
			"oneOf",
			"allOf",
			"$ref",
			"const",
			"enum"
	);

	private static final BigDecimal INT_MAX =
			BigDecimal.valueOf(Integer.MAX_VALUE);
	private static final BigDecimal INT_MIN =
			BigDecimal.valueOf(Integer.MIN_VALUE);

	/**
	 * Normalizes a tool JSON Schema for the Kiro upstream, which rejects an empty
	 * {@code required} array. A missing {@code required} is therefore never filled in.
	 */
	public static JsonNode buildSchemaJson(JsonNode parameters) {
		if (parameters != null && parameters.isObject()) {
			ObjectNode schemaNode = parameters.deepCopy().asObject();
			clean(schemaNode);
			if (!schemaNode.has("type")) {
				schemaNode.put("type", "object");
			}
			if (!schemaNode.has("properties")) {
				schemaNode.putObject("properties");
			}
			return schemaNode;
		}

		ObjectNode emptyObj = MAPPER.createObjectNode();
		emptyObj.put("type", "object");
		emptyObj.putObject("properties");
		return emptyObj;
	}

	/**
	 * Normalizes a schema node and every node below it. Applies at all depths, not just
	 * the root: the bounds and untyped properties the upstream rejects sit nested inside
	 * {@code properties} and {@code items}.
	 */
	public static void clean(ObjectNode node) {
		node.remove("additionalProperties");

		JsonNode required = node.get("required");
		if (required != null) {
			if (!required.isArray() || required.isEmpty()) {
				node.remove("required");
			}
		}

		clampNumericBounds(node);
		ensureTypeResolvable(node);

		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			JsonNode value = entry.getValue();
			if (value.isObject()) {
				clean((ObjectNode) value);
			} else if (value.isArray()) {
				for (JsonNode item : value) {
					if (item.isObject()) {
						clean((ObjectNode) item);
					}
				}
			}
		}
	}

	/**
	 * Pulls out-of-range integral bounds back to the int32 edge. Clamping rather than
	 * dropping keeps the constraint's meaning — the property stays bounded — and a
	 * clamped ceiling of {@link Integer#MAX_VALUE} is past any value a tool argument
	 * realistically carries. Fractional bounds are left alone: they are not what
	 * overflows, and rounding one would tighten a constraint the client authored.
	 */
	private static void clampNumericBounds(ObjectNode node) {
		for (String bound : NUMERIC_BOUNDS) {
			JsonNode value = node.get(bound);
			if (value == null || !value.isIntegralNumber()) {
				continue;
			}
			BigDecimal magnitude = value.decimalValue();
			if (magnitude.compareTo(INT_MAX) > 0) {
				node.put(bound, Integer.MAX_VALUE);
			} else if (magnitude.compareTo(INT_MIN) < 0) {
				node.put(bound, Integer.MIN_VALUE);
			}
		}
	}

	/**
	 * Gives a described-but-untyped property a type. {@code description} is the marker
	 * for "this node is a schema the client wrote", which keeps containers such as
	 * {@code properties} out of scope.
	 * <p>
	 * {@code object} is the choice here even though some of these properties accept an
	 * array or a scalar too. JSON Schema has no "any", and a type union
	 * ({@code ["object","array",...]}) is the riskier bet against a validator that
	 * already rejects more ordinary keywords. The narrowing only affects how the
	 * upstream advertises the argument; the tool's own description still documents the
	 * real shape.
	 */
	private static void ensureTypeResolvable(ObjectNode node) {
		if (!node.has("description")) {
			return;
		}
		for (String indicator : TYPE_INDICATORS) {
			if (node.has(indicator)) {
				return;
			}
		}
		node.put("type", "object");
	}
}
