package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.kiro.KiroEvent;
import lombok.RequiredArgsConstructor;

import java.io.PrintWriter;
import java.util.List;

/**
 * Renders one Antigravity response in whichever wire format the client asked for, keeping
 * every per-format decision out of {@link AntigravityFacade}.
 * <p>
 * An instance serves one streaming response: it holds the writer and the response id, so the
 * caller does not thread them through every call. The non-streaming shape needs no state and
 * is available as {@link #nonStreamingBody}.
 */
@RequiredArgsConstructor
final class AntigravityResponseWriter {

	private final StreamConverter streamConverter;
	private final PrintWriter writer;
	private final Format format;
	private final String id;
	private final String model;

	/**
	 * The whole response as one object, for a client that did not ask to stream. Kept here so
	 * every per-format decision lives in one file, even though nothing is written.
	 */
	static Object nonStreamingBody(
			StreamConverter streamConverter,
			Format format,
			String id,
			String model,
			String text,
			int inputTokens,
			int outputTokens
	) throws Exception {
		return switch (format) {
			case ANTHROPIC -> streamConverter.toAnthropicNonStreaming(
					id, model, text, null, inputTokens, outputTokens
			);
			case COMPLETION -> streamConverter.toOpenAiNonStreaming(
					List.of(KiroEvent.content(text)), model, null, inputTokens, outputTokens
			);
			case RESPONSES -> streamConverter.toResponsesNonStreaming(
					id, model, text, null, inputTokens, outputTokens
			);
		};
	}

	/** The opening events the format requires, if any. Flushes so the client sees them. */
	void preamble(int inputTokens) throws Exception {
		String opening = switch (format) {
			case ANTHROPIC -> streamConverter.toAnthropicPreamble(id, model, inputTokens);
			case RESPONSES -> streamConverter.toResponsesCreated(id, model)
			                  + streamConverter.toResponsesOutputItemAdded(id)
			                  + streamConverter.toResponsesContentPartAdded();
			case COMPLETION -> null;
		};
		if (opening != null) {
			write(opening);
		}
	}

	void textDelta(String text) throws Exception {
		write(switch (format) {
			case ANTHROPIC -> streamConverter.toAnthropicDelta(text);
			case COMPLETION ->
					AntigravityStreamConverter.buildChunkPublic(id, model, text);
			case RESPONSES -> streamConverter.toResponsesTextDelta(text);
		});
	}

	/** Writes a tool call. Some formats have nothing to emit for one, hence the null check. */
	void toolCall(KiroEvent event, int toolIndex) throws Exception {
		String chunk = switch (format) {
			case ANTHROPIC -> streamConverter.toAnthropicToolUse(event, toolIndex);
			case COMPLETION -> streamConverter.toOpenAiChunk(event, model, id);
			case RESPONSES -> streamConverter.toResponsesToolCall(event, toolIndex);
		};
		if (chunk != null) {
			write(chunk);
		}
	}

	/**
	 * The closing events for the format. A stream that made tool calls ends without a stop
	 * chunk, since the client is expected to come back with the results.
	 */
	void finale(
			String fullContent,
			boolean hasToolUse,
			int inputTokens,
			int outputTokens
	) throws Exception {
		write(switch (format) {
			case ANTHROPIC -> streamConverter.toAnthropicFinale(outputTokens, hasToolUse);
			case COMPLETION -> hasToolUse
					? AntigravityStreamConverter.buildDoneEvent()
					: AntigravityStreamConverter.buildStopChunk(model, id)
					  + AntigravityStreamConverter.buildDoneEvent();
			case RESPONSES -> {
				String completed = streamConverter.toResponsesCompleted(
						id, model, fullContent, List.of(), inputTokens, outputTokens
				);
				yield hasToolUse
						? completed
						: streamConverter.toResponsesTextDone(fullContent, id) + completed;
			}
		});
	}

	private void write(String chunk) {
		writer.write(chunk);
		writer.flush();
	}
}
