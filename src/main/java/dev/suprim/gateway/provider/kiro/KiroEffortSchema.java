package dev.suprim.gateway.provider.kiro;

import java.util.List;
import java.util.Map;

/**
 * Reads the reasoning-effort levels a model accepts out of its
 * {@code additionalModelRequestFieldsSchema}, which is the only place Kiro reports them.
 * <p>
 * There is no boolean saying whether a model supports effort at all — the presence of the enum
 * is the signal. Models with no schema, or a schema without an effort enum, report nothing.
 */
final class KiroEffortSchema {

	/**
	 * Schema properties that wrap the effort enum, in the order to look. Claude models nest it
	 * under {@code output_config}, GPT ones under {@code reasoning}.
	 */
	private static final List<String> WRAPPERS = List.of(
			"output_config",
			"reasoning"
	);

	private KiroEffortSchema() {}

	/**
	 * Copies {@code effortLevels} and, when the schema names one, {@code defaultEffort} onto
	 * {@code entry}. Leaves {@code entry} untouched when the model advertises no effort enum.
	 */
	static void copyLevels(
			Map<String, Object> upstream,
			Map<String, Object> entry
	) {
		if (!(upstream.get("additionalModelRequestFieldsSchema")
				      instanceof Map<?, ?> schema) ||
		    !(schema.get("properties") instanceof Map<?, ?> properties)) {
			return;
		}
		for (String wrapper : WRAPPERS) {
			if (properties.get(wrapper) instanceof Map<?, ?> group &&
			    group.get("properties") instanceof Map<?, ?> groupProperties &&
			    groupProperties.get("effort") instanceof Map<?, ?> effort &&
			    effort.get("enum") instanceof List<?> levels
			) {
				entry.put("effortLevels", List.copyOf(levels));
				if (effort.get("default") instanceof String defaultLevel) {
					entry.put("defaultEffort", defaultLevel);
				}
				return;
			}
		}
	}
}
