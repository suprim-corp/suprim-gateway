package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.StreamHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.nonNull;

@RequiredArgsConstructor
@Component
public class KiroFormatConverter {

	private final StreamConverter streamConverter;

	public String generateId(Format format) {
		return switch (format) {
			case COMPLETION -> "chatcmpl-" + UUID.randomUUID();
			case ANTHROPIC -> "msg_" + UUID.randomUUID().toString().replace(
					"-",
					""
			).substring(0, 20);
			case RESPONSES -> "resp_" + UUID.randomUUID()
			                                .toString()
			                                .replace("-", "")
			                                .substring(0, 24);
		};
	}

	public String preamble(
			Format format,
			String id,
			String model,
			int inputTokens
	) throws Exception {
		return switch (format) {
			case COMPLETION -> "";
			case ANTHROPIC -> streamConverter.toAnthropicPreamble(
					id,
					model,
					inputTokens
			);
			case RESPONSES -> streamConverter.toResponsesCreated(id, model) +
			                  streamConverter.toResponsesOutputItemAdded(id) +
			                  streamConverter.toResponsesContentPartAdded();
		};
	}

	public String convertEvent(
			KiroEvent event,
			Format format,
			String model,
			String id
	) throws Exception {
		boolean hasContent =
				"content".equals(event.type()) && nonNull(event.content());
		boolean stopSign = "tool_use".equals(event.type()) && event.toolStop();

		return switch (format) {
			case COMPLETION -> streamConverter.toOpenAiChunk(event, model, id);
			case ANTHROPIC -> {
				if (hasContent) {
					yield streamConverter.toAnthropicDelta(event.content());
				}
				if (stopSign) {
					yield streamConverter.toAnthropicToolUse(event, 1);
				}
				yield null;
			}
			case RESPONSES -> {
				if (hasContent) {
					yield streamConverter.toResponsesTextDelta(event.content());
				}
				if (stopSign) {
					yield streamConverter.toResponsesToolCall(event, 1);
				}
				yield null;
			}
		};
	}

	public String finale(
			Format format,
			String id,
			String model,
			StreamHandler.StreamResult result,
			int inputTokens
	) throws Exception {
		return switch (format) {
			case COMPLETION -> streamConverter.toOpenAiStopChunk(model, id) +
			                   streamConverter.toOpenAiDone();
			case ANTHROPIC -> streamConverter.toAnthropicFinale(
					result.outputTokens(),
					result.hasToolUse()
			);
			case RESPONSES ->
					streamConverter.toResponsesTextDone(result.content(), id) +
					streamConverter.toResponsesCompleted(
							id,
							model,
							result.content(),
							List.of(),
							inputTokens,
							result.outputTokens()
					);
		};
	}

	public Object nonStreamBody(
			Format format,
			String id,
			String model,
			String content,
			int inputTokens,
			int outputTokens
	) {
		return switch (format) {
			case COMPLETION -> streamConverter.toOpenAiNonStreaming(
					List.of(KiroEvent.content(content)), model);
			case ANTHROPIC -> streamConverter.toAnthropicNonStreaming(
					id,
					model,
					content,
					inputTokens,
					outputTokens
			);
			case RESPONSES -> streamConverter.toResponsesNonStreaming(
					id,
					model,
					content,
					inputTokens,
					outputTokens
			);
		};
	}
}
