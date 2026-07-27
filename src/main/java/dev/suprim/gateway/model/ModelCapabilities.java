package dev.suprim.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Per-model capability flags, shaped after the Anthropic Models API so a client
 * that reads {@code capabilities.image_input.supported} finds what it expects.
 * <p>
 * Only capabilities an upstream actually reports are filled in. A provider that
 * says nothing about a capability leaves it null and it is omitted from the
 * response, which is the honest answer: absent means unknown, not unsupported.
 * Capabilities no upstream reports at all — batch, citations, code execution,
 * context management — are deliberately not modelled rather than hardcoded to
 * false, since serving them as {@code false} for a model that does support them
 * would be worse than staying silent.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelCapabilities(
		Support imageInput,
		Support pdfInput,
		Support audioInput,
		Support videoInput,
		Thinking thinking,
		Effort effort,
		Support structuredOutputs,
		Support promptCaching
) {

	/**
	 * Whether one capability is supported.
	 */
	public record Support(boolean supported) {

		public static final Support YES = new Support(true);
		public static final Support NO = new Support(false);

		/**
		 * Null when {@code supported} is unknown, so the field is omitted.
		 */
		public static Support of(Boolean supported) {
			return supported == null ? null : supported ? YES : NO;
		}
	}

	/**
	 * Thinking support plus the token budget the upstream advertises. Antigravity
	 * reports {@code -1} for "no fixed budget", which is passed through as-is
	 * rather than normalized, since it is a meaningful value upstream.
	 */
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Thinking(
			boolean supported,
			Integer budgetTokens,
			Integer minBudgetTokens
	) {}

	/**
	 * Reasoning-effort support and the levels the upstream accepts. Kiro
	 * advertises these per model in {@code additionalModelRequestFieldsSchema};
	 * every other provider leaves this null.
	 */
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Effort(
			boolean supported,
			List<String> levels,
			String defaultLevel
	) {}
}
