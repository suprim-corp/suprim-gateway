package dev.suprim.gateway.provider.kiro.payload;

/**
 * Kiro rejects a user message with empty content, so turns that carry no text of their own
 * need filler. The filler reaches the model as literal user speech, so it has to describe
 * itself: a bare "." reads as a real (and empty-looking) user message and the model
 * sometimes answers it instead of the turn's actual payload.
 */
final class ContentPlaceholder {

	/**
	 * Turn whose payload is tool results rather than text.
	 */
	static final String TOOL_RESULTS = "[tool results]";

	/**
	 * Synthesized turn that only asks the model to keep going.
	 */
	static final String CONTINUE = "[continue]";

	/**
	 * User turn whose content is non-textual, typically images only.
	 */
	static final String NO_TEXT = "[no text content]";

	private ContentPlaceholder() {}
}
