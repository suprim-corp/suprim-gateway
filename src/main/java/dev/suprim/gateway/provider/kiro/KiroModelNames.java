package dev.suprim.gateway.provider.kiro;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Exposed id and display name for one Kiro model.
 * <p>
 * The canonical id keeps Kiro's own dotted spelling ({@code claude-opus-4.6}) because that is what
 * the upstream accepts; only what the gateway publishes is hyphenated. Requests still arrive in
 * either spelling — {@code ModelResolver} folds them back onto the canonical id.
 */
public final class KiroModelNames {

	private static final Pattern DOT_VERSION = Pattern.compile(
			"^(claude-(?:sonnet|opus|haiku)-)(\\d+)\\.(\\d+)$");

	/** Words whose casing does not follow from capitalising the first letter. */
	private static final Map<String, String> WORDS = Map.of(
			"gpt", "GPT",
			"glm", "GLM",
			"oss", "OSS",
			"deepseek", "DeepSeek",
			"minimax", "MiniMax"
	);

	private KiroModelNames() {}

	/**
	 * Kiro names some models with a dotted version ({@code claude-sonnet-4.6}) that the gateway
	 * exposes hyphenated. Ids without a dotted version pass through unchanged.
	 */
	public static String exposedId(String canonicalId) {
		Matcher matcher = DOT_VERSION.matcher(canonicalId);
		return matcher.matches()
				? matcher.group(1) + matcher.group(2) + "-" + matcher.group(3)
				: canonicalId;
	}

	/**
	 * A readable name derived from the id: {@code claude-opus-4.8} reads as "Opus 4.8". The
	 * vendor prefix is dropped for Claude models, whose variant already identifies them.
	 */
	public static String displayName(String canonicalId) {
		String name = canonicalId.startsWith("claude-")
				? canonicalId.substring("claude-".length())
				: canonicalId;
		return Arrays.stream(name.split("-"))
		             .map(KiroModelNames::word)
		             .collect(Collectors.joining(" "));
	}

	private static String word(String value) {
		String known = WORDS.get(value);
		if (known != null) {
			return known;
		}
		return value.isEmpty()
				? value
				: Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
