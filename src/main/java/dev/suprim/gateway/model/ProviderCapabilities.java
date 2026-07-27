package dev.suprim.gateway.model;

import dev.suprim.gateway.model.ModelCapabilities.Effort;
import dev.suprim.gateway.model.ModelCapabilities.Support;
import dev.suprim.gateway.model.ModelCapabilities.Thinking;

import java.util.List;
import java.util.Map;

/**
 * Reads the capability keys a provider's {@code listModels} put on its model
 * maps and turns them into a {@link ModelCapabilities}.
 * <p>
 * Only two providers report anything: Antigravity, whose
 * {@code fetchAvailableModels} carries modality booleans, MIME types and
 * thinking budgets, and Kiro, whose {@code ListAvailableModels} carries
 * {@code supportedInputTypes}, prompt caching, token limits and the
 * reasoning-effort enum. xAI's and Codex's listings carry the model id and
 * little else, so their models are served without capabilities rather than with
 * invented ones.
 * <p>
 * Every read is absence-tolerant: a key the provider did not set leaves the
 * corresponding capability null, which {@code @JsonInclude(NON_NULL)} then omits
 * from the response. Absent means unknown, not unsupported.
 */
final class ProviderCapabilities {

	private ProviderCapabilities() {}

	/**
	 * Null when the provider reported no capability at all, so the field is omitted.
	 */
	static ModelCapabilities from(Map<String, Object> model) {
		ModelCapabilities capabilities =
				ModelCapabilities.builder()
				                 .imageInput(
						                 Support.of(
								                 bool(
										                 model,
										                 "supportsImages"
								                 )
						                 )
				                 )
				                 .videoInput(
						                 Support.of(
								                 bool(
										                 model,
										                 "supportsVideo"
								                 )
						                 )
				                 )
				                 .pdfInput(
						                 Support.of(
								                 bool(
										                 model,
										                 "supportsPdf"
								                 )
						                 )
				                 )
				                 .audioInput(
						                 Support.of(
								                 bool(
										                 model,
										                 "supportsAudio"
								                 )
						                 )
				                 )
				                 .promptCaching(
						                 Support.of(
								                 bool(
										                 model,
										                 "supportsPromptCaching"
								                 )
						                 )
				                 )
				                 .thinking(thinking(model))
				                 .effort(effort(model))
				                 .build();
		return isEmpty(capabilities) ? null : capabilities;
	}

	static Integer maxInputTokens(Map<String, Object> model) {
		return integer(model, "maxInputTokens");
	}

	static Integer maxOutputTokens(Map<String, Object> model) {
		return integer(model, "maxOutputTokens");
	}

	/**
	 * Present only when the provider says whether thinking is supported. The budgets are
	 * extra detail on top of that answer, never the answer itself.
	 */
	private static Thinking thinking(Map<String, Object> model) {
		Boolean supported = bool(model, "supportsThinking");
		if (supported == null) {
			return null;
		}
		return Thinking.builder()
		               .supported(supported)
		               .budgetTokens(integer(model, "thinkingBudget"))
		               .minBudgetTokens(integer(model, "minThinkingBudget"))
		               .build();
	}

	/**
	 * Present only when the provider advertises concrete effort levels. An empty level list
	 * is treated as no answer, since a client cannot act on it.
	 */
	private static Effort effort(Map<String, Object> model) {
		if (!(model.get("effortLevels") instanceof List<?> levels) ||
		    levels.isEmpty()) {
			return null;
		}
		return Effort.builder()
		             .supported(true)
		             .levels(levels.stream().map(String::valueOf).toList())
		             .defaultLevel(
				             model.get("defaultEffort") instanceof String level
						             ? level
						             : null
		             )
		             .build();
	}

	private static boolean isEmpty(ModelCapabilities capabilities) {
		return capabilities.imageInput() == null &&
		       capabilities.videoInput() == null &&
		       capabilities.pdfInput() == null &&
		       capabilities.audioInput() == null &&
		       capabilities.promptCaching() == null &&
		       capabilities.thinking() == null &&
		       capabilities.effort() == null &&
		       capabilities.structuredOutputs() == null;
	}

	private static Boolean bool(Map<String, Object> model, String key) {
		return model.get(key) instanceof Boolean value ? value : null;
	}

	private static Integer integer(Map<String, Object> model, String key) {
		return model.get(key) instanceof Number value
				? value.intValue()
				: null;
	}
}
