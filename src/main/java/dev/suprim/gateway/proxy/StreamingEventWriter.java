package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.sse.AnthropicSsePayloads;
import dev.suprim.gateway.proxy.sse.ResponsesSsePayloads;

import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

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
	private final int inputTokens;

	private boolean messagePreambleSent = false;
	private boolean thinkingBlockOpen = false;
	private boolean textBlockOpen = false;
	private boolean hasContent = false;
	private boolean hasOutput = false;
	private boolean hasToolUse = false;
	private KiroEvent.Usage usage;
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
		this(writer, converter, format, model, thinkingEnabled, 0);
	}

	public StreamingEventWriter(
			PrintWriter writer,
			StreamConverter converter,
			Format format,
			String model,
			boolean thinkingEnabled,
			int inputTokens
	) {
		this.writer = writer;
		this.converter = converter;
		this.format = format;
		this.model = model;
		this.id = "chatcmpl-" + UUID.randomUUID();
		this.thinkingEnabled = thinkingEnabled;
		this.inputTokens = inputTokens;
	}

	public boolean hasContent() {
		return hasContent;
	}

	public boolean hasOutput() {
		return hasOutput;
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
		if ("usage".equals(event.type())) {
			if (!messagePreambleSent && event.usage() != null) {
				usage = mergeUsage(usage, event.usage());
			}
			return;
		}
		if (!"content".equals(event.type()) &&
		    !"reasoning".equals(event.type()) &&
		    !"tool_use".equals(event.type())
		) {
			return;
		}

		if ("reasoning".equals(event.type()) && !thinkingEnabled) {
			return;
		}

		hasOutput = true;
		switch (format) {
			case ANTHROPIC -> writeAnthropic(event);
			case RESPONSES -> writeResponses(event);
			default -> writeCompletion(event);
		}
		writer.flush();
	}

	public void finish() throws Exception {
		finish(0);
	}

	public void finish(int outputTokens) throws Exception {
		switch (format) {
			case ANTHROPIC -> finishAnthropic(outputTokens);
			case RESPONSES -> finishResponses(outputTokens);
			default -> finishCompletion(outputTokens);
		}
		writer.flush();
	}

	private void writeAnthropic(KiroEvent event) throws Exception {
		if (!messagePreambleSent) {
			messagePreambleSent = true;
			writer.write(
					converter.toAnthropicEvent(
							"message_start",
							AnthropicSsePayloads.MessageStart.of(
								id,
								model,
								AnthropicSsePayloads.Usage.start(
										usage != null && usage.promptTokens() != null
												? usage.promptTokens()
												: inputTokens,
										usage == null ? null : usage.cacheReadTokens(),
										usage == null ? null : usage.cacheCreationTokens()
								)
						)
					)
			);
		}

		if ("tool_use".equals(event.type()) && event.toolStop()) {
			if (textBlockOpen) {
				writer.write(
						converter.toAnthropicEvent(
								"content_block_stop",
								AnthropicSsePayloads.ContentBlockStop.of(blockIndex)
						)
				);
				blockIndex++;
				textBlockOpen = false;
			}
			hasContent = true;
			hasToolUse = true;
			writer.write(converter.toAnthropicToolUse(event, blockIndex));
			blockIndex++;
		} else if ("reasoning".equals(event.type())) {
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

		if ("tool_use".equals(event.type()) && event.toolStop()) {
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
			hasContent = true;
			writer.write(converter.toResponsesToolCall(event, blockIndex));
			blockIndex++;
		} else if ("reasoning".equals(event.type())) {
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
		} else if ("tool_use".equals(event.type()) && event.toolStop()) {
			hasContent = true;
			String sse = converter.toOpenAiChunk(event, model, id);
			if (sse != null) {
				writer.write(sse);
			}
		} else if ("content".equals(event.type())) {
			hasContent = true;
			String sse = converter.toOpenAiChunk(event, model, id);
			if (sse != null) {
				writer.write(sse);
			}
		}
	}

	private void finishAnthropic(int outputTokens) throws Exception {
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
		String stopReason = hasToolUse ? "tool_use" : "end_turn";
		writer.write(
				converter.toAnthropicEvent(
						"message_delta",
						AnthropicSsePayloads.MessageDelta.withStopReason(
								stopReason,
								outputTokens
						)
				)
		);
		writer.write(
				"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
	}

	private void finishResponses(int outputTokens) throws Exception {
		writer.write(converter.toResponsesTextDone("", id));
		writer.write(
				converter.toResponsesCompleted(
						id,
						model,
						"",
						List.of(),
						inputTokens,
						outputTokens
				)
		);
	}

	private static KiroEvent.Usage mergeUsage(
			KiroEvent.Usage current,
			KiroEvent.Usage update
	) {
		return KiroEvent.Usage.builder()
		                      .promptTokens(update.promptTokens() != null
				                      ? update.promptTokens()
				                      : value(current, KiroEvent.Usage::promptTokens))
		                      .outputTokens(update.outputTokens() != null
				                      ? update.outputTokens()
				                      : value(current, KiroEvent.Usage::outputTokens))
		                      .cacheReadTokens(update.cacheReadTokens() != null
				                      ? update.cacheReadTokens()
				                      : value(current, KiroEvent.Usage::cacheReadTokens))
		                      .cacheCreationTokens(update.cacheCreationTokens() != null
				                      ? update.cacheCreationTokens()
				                      : value(current, KiroEvent.Usage::cacheCreationTokens))
		                      .contextPercentage(update.contextPercentage() != null
				                      ? update.contextPercentage()
				                      : decimalValue(
						                      current,
						                      KiroEvent.Usage::contextPercentage
				                      ))
		                      .build();
	}

	private static Integer value(
			KiroEvent.Usage usage,
			Function<KiroEvent.Usage, Integer> accessor
	) {
		return usage == null ? null : accessor.apply(usage);
	}

	private static Double decimalValue(
			KiroEvent.Usage usage,
			Function<KiroEvent.Usage, Double> accessor
	) {
		return usage == null ? null : accessor.apply(usage);
	}

	private void finishCompletion(int outputTokens) throws Exception {
		String finishReason = hasToolUse ? "tool_calls" : "stop";
		writer.write(
				converter.toOpenAiFinishChunk(
						model, id, finishReason, inputTokens, outputTokens
				)
		);
		writer.write(converter.toOpenAiDone());
	}
}
