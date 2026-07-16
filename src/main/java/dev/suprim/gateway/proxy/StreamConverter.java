package dev.suprim.gateway.proxy;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.sse.AnthropicSsePayloads;
import dev.suprim.gateway.proxy.sse.CompletionsSsePayloads.CompletionChunk;
import dev.suprim.gateway.proxy.sse.CompletionsSsePayloads.CompletionDelta;
import dev.suprim.gateway.proxy.sse.CompletionsSsePayloads.CompletionMessage;
import dev.suprim.gateway.proxy.sse.CompletionsSsePayloads.CompletionResponse;
import dev.suprim.gateway.proxy.sse.CompletionsSsePayloads.ToolCallDelta;
import dev.suprim.gateway.proxy.sse.ResponsesSsePayloads;
import lombok.Builder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class StreamConverter {

	private final JsonMapper mapper = new JsonMapper();

	@Builder
	@JsonPropertyOrder({"type", "id", "call_id", "name", "arguments"})
	public record FunctionCallItem(
			String type,
			String id,
			String call_id,
			String name,
			String arguments
	) {
		static FunctionCallItem of(
				String id,
				String callId,
				String name,
				String arguments
		) {
			return FunctionCallItem.builder()
			                       .type("function_call")
			                       .id(id)
			                       .call_id(callId)
			                       .name(name)
			                       .arguments(arguments)
			                       .build();
		}
	}

	public String toOpenAiReasoningChunk(
			KiroEvent event,
			String model,
			String id
	) throws Exception {
		CompletionChunk chunk = CompletionChunk.of(
				id,
				model,
				CompletionDelta.reasoning(event.content())
		);
		return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
	}

	public String toOpenAiChunk(
			KiroEvent event,
			String model,
			String id
	) throws Exception {
		if ("content".equals(event.type())) {
			CompletionChunk chunk = CompletionChunk.of(
					id, model, CompletionDelta.content(event.content())
			);
			return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
		}

		if ("tool_use".equals(event.type()) && event.toolStop()) {
			String toolId = Optional.ofNullable(event.toolUseId())
			                        .orElse("call_" + UUID.randomUUID());
			CompletionChunk chunk = CompletionChunk.of(
					id, model,
					CompletionDelta.toolCall(
							ToolCallDelta.of(
									toolId,
									event.toolName(),
									event.toolInput()
							)
					)
			);
			return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
		}

		return null;
	}

	public String toOpenAiDone() {
		return "data: [DONE]\n\n";
	}

	public String toOpenAiStopChunk(String model, String id) throws Exception {
		CompletionChunk chunk = CompletionChunk.stop(id, model);
		return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
	}

	public String toOpenAiFinishChunk(String model, String id, String finishReason) throws Exception {
		CompletionChunk chunk = CompletionChunk.finish(id, model, finishReason);
		return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
	}

	public CompletionResponse toOpenAiNonStreaming(
			List<KiroEvent> events,
			String model,
			String reasoning
	) {
		StringBuilder contentBuilder = new StringBuilder();
		List<ToolCallDelta> toolCalls = new ArrayList<>();

		for (KiroEvent event : events) {
			if ("content".equals(event.type()) && event.content() != null) {
				contentBuilder.append(event.content());
			} else if ("tool_use".equals(event.type()) && event.toolStop()) {
				String toolId = Optional.ofNullable(event.toolUseId())
				                        .orElse("call_" + UUID.randomUUID());
				toolCalls.add(
						ToolCallDelta.of(
								toolId,
								event.toolName(),
								event.toolInput()
						)
				);
			}
		}

		String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
		CompletionMessage message = CompletionMessage.of(
				contentBuilder.toString(),
				reasoning,
				toolCalls
		);

		return CompletionResponse.of(model, message, finishReason);
	}

	public String toAnthropicEvent(
			String eventType,
			Object data
	) throws Exception {
		return "event: " + eventType + "\ndata: " + mapper.writeValueAsString(
				data) + "\n\n";
	}

	public String toAnthropicPreamble(
			String id,
			String model,
			int inputTokens
	) throws Exception {
		return toAnthropicEvent(
				"message_start",
				AnthropicSsePayloads.MessageStart.of(id, model)
		)
		       + toAnthropicEvent(
				"content_block_start",
				AnthropicSsePayloads.ContentBlockStart.text(0)
		);
	}

	public String toAnthropicDelta(String text) throws Exception {
		return toAnthropicEvent(
				"content_block_delta",
				AnthropicSsePayloads.ContentBlockDelta.text(0, text)
		);
	}

	public String toAnthropicToolUse(
			KiroEvent event,
			int index
	) throws Exception {
		String toolId = event.toolUseId() != null ? event.toolUseId() :
				"toolu_" + UUID.randomUUID()
				               .toString()
				               .replace("-", "")
				               .substring(0, 20);
		String input = event.toolInput() != null ? event.toolInput() : "{}";
		return toAnthropicEvent(
				"content_block_start",
				AnthropicSsePayloads.ContentBlockStart.toolUse(
						index,
						toolId,
						event.toolName()
				)
		)
		       + toAnthropicEvent(
				"content_block_delta",
				AnthropicSsePayloads.ContentBlockDelta.inputJson(index, input)
		)
		       + toAnthropicEvent(
				"content_block_stop",
				AnthropicSsePayloads.ContentBlockStop.of(index)
		);
	}

	public String toAnthropicFinale(
			int outputTokens,
			boolean hasToolUse
	) throws Exception {
		String stopReason = hasToolUse ? "tool_use" : "end_turn";
		return toAnthropicEvent(
				"content_block_stop",
				AnthropicSsePayloads.ContentBlockStop.of(0)
		)
		       + toAnthropicEvent(
				"message_delta",
				AnthropicSsePayloads.MessageDelta.endTurn()
		)
		       + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
	}

	public AnthropicSsePayloads.AnthropicResponse toAnthropicNonStreaming(
			String id,
			String model,
			String content,
			String reasoning,
			int inputTokens,
			int outputTokens
	) {
		List<Object> contentBlocks = new ArrayList<>();
		if (reasoning != null) {
			contentBlocks.add(
					AnthropicSsePayloads.ThinkingContentBlock.of(
							reasoning
					)
			);
		}
		contentBlocks.add(AnthropicSsePayloads.TextContentBlock.of(content));

		return AnthropicSsePayloads.AnthropicResponse.of(
				id, model, contentBlocks, inputTokens, outputTokens
		);
	}

	public ResponsesSsePayloads.ResponsesResponse toResponsesNonStreaming(
			String id,
			String model,
			String content,
			String reasoning,
			int inputTokens,
			int outputTokens
	) {
		List<Object> output = new ArrayList<>();
		if (reasoning != null) {
			output.add(ResponsesSsePayloads.ReasoningOutputItem.of(reasoning));
		}
		output.add(ResponsesSsePayloads.MessageOutputItem.of(content));

		return ResponsesSsePayloads.ResponsesResponse.of(
				id, model, output, inputTokens, outputTokens
		);
	}

	public String toResponsesSse(Object data) throws Exception {
		return "data: " + mapper.writeValueAsString(data) + "\n\n";
	}

	public String toResponsesCreated(
			String responseId,
			String model
	) throws Exception {
		return toResponsesSse(
				ResponsesSsePayloads.ResponseCreated.of(
						responseId,
						model
				)
		);
	}

	public String toResponsesOutputItemAdded(String responseId) throws Exception {
		String msgId = "msg_" + responseId.substring(5);
		return toResponsesSse(ResponsesSsePayloads.OutputItemAdded.message(msgId));
	}

	public String toResponsesContentPartAdded() throws Exception {
		return toResponsesSse(ResponsesSsePayloads.ContentPartAdded.text());
	}

	public String toResponsesTextDelta(String delta) throws Exception {
		return toResponsesSse(ResponsesSsePayloads.OutputTextDelta.of(delta));
	}

	public String toResponsesTextDone(
			String fullText,
			String responseId
	) throws Exception {
		String msgId = "msg_" + responseId.substring(5);
		return toResponsesSse(ResponsesSsePayloads.OutputTextDone.of(fullText))
		       + toResponsesSse(
				ResponsesSsePayloads.ContentPartDone.text(
						fullText
				)
		)
		       + toResponsesSse(
				ResponsesSsePayloads.OutputItemDone.message(
						msgId,
						fullText
				)
		);
	}

	public String toResponsesToolCall(
			KiroEvent event,
			int outputIndex
	) throws Exception {
		String callId = Optional.ofNullable(event.toolUseId())
		                        .orElse(
										UUID.randomUUID()
		                                    .toString()
		                                    .substring(0, 8)
		                        );
		String fcId = "fc_" + callId;
		String name = Optional.ofNullable(event.toolName()).orElse("unknown");
		String args = Optional.ofNullable(event.toolInput()).orElse("{}");

		FunctionCallItem emptyItem = FunctionCallItem.of(
				fcId,
				callId,
				name,
				""
		);
		FunctionCallItem doneItem = FunctionCallItem.of(
				fcId,
				callId,
				name,
				args
		);

		return toResponsesSse(
				ResponsesSsePayloads.FunctionCallOutputItemAdded.of(
						outputIndex,
						emptyItem
				)
		)
		       +
		       toResponsesSse(
				       ResponsesSsePayloads.FunctionCallArgumentsDelta.of(
						       fcId,
						       callId,
						       outputIndex,
						       args
				       )
		       )
		       +
		       toResponsesSse(
				       ResponsesSsePayloads.FunctionCallArgumentsDone.of(
						       fcId,
						       callId,
						       outputIndex,
						       args
				       )
		       )
		       +
		       toResponsesSse(
				       ResponsesSsePayloads.FunctionCallOutputItemDone.of(
						       outputIndex,
						       doneItem
				       )
		       );
	}

	public String toResponsesCompleted(
			String responseId,
			String model,
			String fullText,
			List<?> toolCalls,
			int inputTokens,
			int outputTokens
	) throws Exception {
		List<Object> output = new ArrayList<>();
		output.add(ResponsesSsePayloads.MessageOutputItem.of(fullText));
		output.addAll(toolCalls);
		return toResponsesSse(
				ResponsesSsePayloads.ResponseCompleted.of(
						responseId, model, output, inputTokens, outputTokens
				)
		);
	}
}
