package dev.suprim.gateway.model;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ModelResolver {

	private static final Map<String, String> ALIASES = Map.of(
			"auto-kiro", "auto"
	);

	private static final Map<String, String> HIDDEN_MODELS = Map.of(
			"claude-3.7-sonnet", "CLAUDE_3_7_SONNET_20250219_V1_0"
	);

	private static final Pattern INVERTED_PATTERN = Pattern.compile(
			"^(claude)-(\\d+(?:\\.\\d+)?)-(\\w+?)(?:-(low|medium|high|xhigh))?$"
	);

	private static final Pattern LEGACY_PATTERN = Pattern.compile(
			"^(claude)-(\\d+)-(\\d+)-(sonnet|opus|haiku)(.*)$"
	);

	private static final Pattern VERSION_DASH_PATTERN = Pattern.compile(
			"^(claude-(?:sonnet|opus|haiku)-)(\\d+)-(\\d+)(-.+)?(\\[\\d+[km]?\\])?$",
			Pattern.CASE_INSENSITIVE
	);

	private final Set<String> cachedModels = ConcurrentHashMap.newKeySet();

	public void setCachedModels(java.util.List<String> models) {
		cachedModels.clear();
		for (String model : models) {
			cachedModels.add(normalize(model));
		}
	}

	public String resolve(String requestedModel) {
		String normalized = canonicalize(requestedModel);
		if (cachedModels.contains(normalized)) return normalized;
		if (HIDDEN_MODELS.containsKey(normalized)) return normalized;

		// passthrough — let Kiro decide
		return normalized;
	}

	public String canonicalize(String requestedModel) {
		if (requestedModel == null || requestedModel.isBlank()) {
			throw new IllegalArgumentException("Model is required");
		}

		String aliased = ALIASES.get(requestedModel);
		if (aliased != null) return aliased;

		String normalized = normalize(requestedModel);
		return ALIASES.getOrDefault(normalized, normalized);
	}

	public Set<String> getAvailableModels() {
		Set<String> all = ConcurrentHashMap.newKeySet();
		all.addAll(cachedModels);
		all.addAll(HIDDEN_MODELS.keySet());
		return all;
	}

	private String normalize(String model) {
		String name = model.trim().toLowerCase();

		// Strip context window suffix [1m], [200k]
		name = name.replaceAll("\\s*\\[\\d+[km]?]$", "");

		// Handle inverted format: claude-4.5-opus-high → claude-opus-4.5
		Matcher invertedMatch = INVERTED_PATTERN.matcher(name);
		if (invertedMatch.matches()) {
			String prefix = invertedMatch.group(1);
			String version = invertedMatch.group(2);
			String variant = invertedMatch.group(3);
			name = prefix + "-" + variant + "-" + version;
		}

		// Handle legacy format: claude-3-7-sonnet → claude-3.7-sonnet
		Matcher legacyMatch = LEGACY_PATTERN.matcher(name);
		if (legacyMatch.matches()) {
			String prefix = legacyMatch.group(1);
			String major = legacyMatch.group(2);
			String minor = legacyMatch.group(3);
			String variant = legacyMatch.group(4);
			String rest = legacyMatch.group(5);
			name = prefix + "-" + major + "." + minor + "-" + variant + rest;
		}

		// Convert trailing version dashes to dots: claude-sonnet-4-5 → claude-sonnet-4.5
		Matcher versionMatch = VERSION_DASH_PATTERN.matcher(name);
		if (versionMatch.matches()) {
			String prefix = versionMatch.group(1);
			String major = versionMatch.group(2);
			String minor = versionMatch.group(3);
			String rest =
					versionMatch.group(4) != null ? versionMatch.group(4) : "";
			String ctx =
					versionMatch.group(5) != null ? versionMatch.group(5) : "";
			name = prefix + major + "." + minor + rest + ctx;
		}

		// Strip date suffix: claude-sonnet-4.5-20250929 → claude-sonnet-4.5
		name = name.replaceAll("-\\d{8}$", "");

		return name;
	}
}
