package dev.suprim.gateway.provider.codex;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * One account's Codex usage, as the providers page consumes it.
 * <p>
 * The upstream reports snake_case fields and moves them between two namings
 * ({@code primary_window} / {@code primary}); this is the settled shape after that is resolved.
 * Percentages are <em>used</em>, not remaining, and the tighter of the two windows is what will
 * actually stop a request.
 * <p>
 * A lookup that failed carries {@code message} instead of figures, and {@code unauthorized} when
 * the credential itself was refused. Absent fields are omitted rather than sent as null, so a
 * failure does not render as a card full of empty figures.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodexUsage(
		String plan,
		Boolean limitReached,
		Window session,
		Window weekly,
		Integer resetCredits,
		String message,
		Boolean unauthorized
) {

	/** One rate-limit window. {@code resetAt} is an ISO timestamp, absent when not reported. */
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Window(int usedPercent, String resetAt) {}

	public static CodexUsage failure(String message) {
		return CodexUsage.builder().message(message).build();
	}

	/**
	 * A failure that also means the credential will not work until re-authorised, so the providers
	 * page can stop presenting the account as connected.
	 */
	public static CodexUsage rejected(String message) {
		return CodexUsage.builder().message(message).unauthorized(true).build();
	}
}
