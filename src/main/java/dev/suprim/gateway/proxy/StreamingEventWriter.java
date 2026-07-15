package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.sse.AnthropicSsePayloads;
import dev.suprim.gateway.proxy.sse.ResponsesSsePayloads;

import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reusable streaming writer that emits KiroEvents in the correct format
 * (ANTHROPIC, RESPONSES, COMPLETION) with proper thinking/content separation.
 */
public class StreamingEventWriter {

	private final PrintWriter writer;
	private final StreamConverter converter;
	private final Format format;
	private final String model;
	private final String id;
	private final boolean thinkingEnabled;

	private boolean messagePreambleSent = false;
	private boolean thinkingBlockOpen = false;
	private boolean textBlockOpen = false;
	private boolean hasContent = false;
	private int blockIndex = 0;

	public StreamingEventWriter(
			PrintWriter writer,
			StreamConverter converter,
			Format format,
			String model
	) {
		this(writer, converter, format, model, true);
	}

	public StreamingEventWriter(
			PrintWriter writer,
			StreamConverter converter,
			Format format,
			String model,
			boolean thinkingEnabled
	) {
		this.writer = writer;
		this.converter = converter;
		this.format = format;
		this.model = model;
		this.id = "chatcmpl-" + UUID.randomUUID();
		this.thinkingEnabled = thinkingEnabled;
	}

	public boolean hasContent() {
		return hasContent;
	}

	public Consumer<KiroEvent> asConsumer() {
		return event -> {
			try {
				write(event);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		};
	}

	public void write(KiroEvent event) throws Exception {
		if (!"content".equals(event.type()) &&
		    !"reasoning".equals(event.type())) {
			return;
		}

		if ("reasoning".equals(event.type()) && !thinkingEnabled) {
			return;
		}

		switch (format) {
			case ANTHROPIC -> writeAnthropic(event);
			case RESPONSES -> writeResponses(event);
			default -> writeCompletion(event);
		}
		writer.flush();
	}

	public void finish() throws Exception {
		switch (format) {
			case ANTHROPIC -> finishAnthropic();
			case RESPONSES -> finishResponses();
			default -> finishCompletion();
		}
		writer.flush();
	}

	private void writeAnthropic(KiroEvent event) throws Exception {
		if (!messagePreambleSent) {
			messagePreambleSent = true;
			writer.write(
					converter.toAnthropicEvent(
							"message_start",
							AnthropicSsePayloads.MessageStart.of(id, model)
					)
			);
		}

		if ("reasoning".equals(event.type())) {
			if (!thinkingBlockOpen) {
				thinkingBlockOpen = true;
				writer.write(
						converter.toAnthropicEvent(
								"content_block_start",
								AnthropicSsePayloads.ContentBlockStart.thinking(
										blockIndex)
						)
				);
			}
			writer.write(
					converter.toAnthropicEvent(
							"content_block_delta",
							AnthropicSsePayloads.ContentBlockDelta.thinking(
									blockIndex,
									event.content()
							)
					)
			);
		} else {
			if (thinkingBlockOpen && !textBlockOpen) {
				writer.write(
						converter.toAnthropicEvent(
								"content_block_stop",
								AnthropicSsePayloads.ContentBlockStop.of(
										blockIndex)
						)
				);
				blockIndex++;
				thinkingBlockOpen = false;
			}
			if (!textBlockOpen) {
				textBlockOpen = true;
				writer.write(
						converter.toAnthropicEvent(
								"content_block_start",
								AnthropicSsePayloads.ContentBlockStart.text(
										blockIndex)
						)
				);
			}
			hasContent = true;
			writer.write(
					converter.toAnthropicEvent(
							"content_block_delta",
							AnthropicSsePayloads.ContentBlockDelta.text(
									blockIndex,
									event.content()
							)
					)
			);
		}
	}

	private void writeResponses(KiroEvent event) throws Exception {
		if (!messagePreambleSent) {
			messagePreambleSent = true;
			writer.write(converter.toResponsesCreated(id, model));
		}

		if ("reasoning".equals(event.type())) {
			if (!thinkingBlockOpen) {
				thinkingBlockOpen = true;
				writer.write(
						converter.toResponsesSse(
								ResponsesSsePayloads.ReasoningSummaryPartAdded.of(
										blockIndex)
						)
				);
			}
			writer.write(
					converter.toResponsesSse(
							ResponsesSsePayloads.ReasoningSummaryTextDelta.of(
									blockIndex,
									event.content()
							)
					)
			);
		} else {
			if (thinkingBlockOpen && !textBlockOpen) {
				writer.write(
						converter.toResponsesSse(
								ResponsesSsePayloads.ReasoningSummaryPartDone.of(
										blockIndex)
						)
				);
				blockIndex++;
				thinkingBlockOpen = false;
			}
			if (!textBlockOpen) {
				textBlockOpen = true;
				writer.write(converter.toResponsesOutputItemAdded(id));
				writer.write(converter.toResponsesContentPartAdded());
			}
			hasContent = true;
			writer.write(converter.toResponsesTextDelta(event.content()));
		}
	}

	private void writeCompletion(KiroEvent event) throws Exception {
		if ("reasoning".equals(event.type())) {
			String sse = converter.toOpenAiReasoningChunk(event, model, id);
			writer.write(sse);
		} else if ("content".equals(event.type())) {
			hasContent = true;
			String sse = converter.toOpenAiChunk(event, model, id);
			if (sse != null) {
				writer.write(sse);
			}
		}
	}

	private void finishAnthropic() throws Exception {
		if (textBlockOpen) {
			writer.write(
					converter.toAnthropicEvent(
							"content_block_stop",
							AnthropicSsePayloads.ContentBlockStop.of(blockIndex)
					)
			);
		} else if (thinkingBlockOpen) {
			writer.write(
					converter.toAnthropicEvent(
							"content_block_stop",
							AnthropicSsePayloads.ContentBlockStop.of(blockIndex)
					)
			);
		}
		writer.write(
				converter.toAnthropicEvent(
						"message_delta",
						AnthropicSsePayloads.MessageDelta.endTurn()
				)
		);
		writer.write(
				"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
	}

	private void finishResponses() throws Exception {
		writer.write(converter.toResponsesTextDone("", id));
		writer.write(
				converter.toResponsesCompleted(
						id,
						model,
						"",
						List.of(),
						0,
						0
				)
		);
	}

	private void finishCompletion() throws Exception {
		writer.write(converter.toOpenAiStopChunk(model, id));
		writer.write(converter.toOpenAiDone());
	}
}
