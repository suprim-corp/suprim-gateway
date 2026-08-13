package dev.suprim.gateway.provider.kiro.payload;

/**
 * Kiro rejects a user or assistant message with empty content, so a turn that carries no text
 * of its own needs filler. The filler reaches the model as literal speech, so it must not read
 * as something the user said: a bare "." looks like an empty user message and the model
 * sometimes answers it instead of the turn's actual payload.
 *
 * <p>Values match jwadow/9router's {@code kiroConversation.js}, which normalizes every turn as
 * {@code content.trim() || "continue"} for user turns and {@code || "..."} for assistant turns.
 */
final class ContentPlaceholder {

	/** Filler for a user turn whose payload is tool results or images rather than text. */
	static final String USER = "continue";

	/** Filler for an assistant turn that produced no text, only tool calls. */
	static final String ASSISTANT = "...";

	private ContentPlaceholder() {}
}
