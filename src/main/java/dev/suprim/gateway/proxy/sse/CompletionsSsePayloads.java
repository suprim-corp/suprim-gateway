package dev.suprim.gateway.proxy.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

public final class CompletionsSsePayloads {

	private CompletionsSsePayloads() {}

	// --- Streaming records ---

	@Builder
	public record CompletionChunk(
			String id, String object, long created, String model,
			List<StreamChoice> choices
	) {
		public static CompletionChunk of(
				String id,
				String model,
				CompletionDelta delta
		) {
			return CompletionChunk.builder()
			                      .id(id)
			                      .object("chat.completion.chunk")
			                      .created(System.currentTimeMillis() / 1000)
			                      .model(model)
			                      .choices(List.of(StreamChoice.of(delta)))
			                      .build();
		}

		public static CompletionChunk stop(String id, String model) {
			return CompletionChunk.builder()
			                      .id(id)
			                      .object("chat.completion.chunk")
			                      .created(System.currentTimeMillis() / 1000)
			                      .model(model)
			                      .choices(List.of(StreamChoice.stop()))
			                      .build();
		}

		public static CompletionChunk finish(
				String id,
				String model,
				String finishReason
		) {
			return CompletionChunk.builder()
			                      .id(id)
			                      .object("chat.completion.chunk")
			                      .created(System.currentTimeMillis() / 1000)
			                      .model(model)
			                      .choices(
					                      List.of(
							                      StreamChoice.builder()
							                                  .index(0)
							                                  .delta(CompletionDelta.empty())
							                                  .finishReason(
									                                  finishReason
							                                  )
							                                  .build()
					                      )
			                      )
			                      .build();
		}
	}

	@Builder
	public record StreamChoice(
			int index, CompletionDelta delta,
			@JsonProperty("finish_reason") @JsonInclude(JsonInclude.Include.ALWAYS) String finishReason
	) {
		public static StreamChoice of(CompletionDelta delta) {
			return StreamChoice.builder().index(0).delta(delta).build();
		}

		public static StreamChoice stop() {
			return StreamChoice.builder()
			                   .index(0)
			                   .delta(CompletionDelta.empty())
			                   .finishReason("stop")
			                   .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CompletionDelta(
			String content,
			@JsonProperty("reasoning_content") String reasoningContent,
			@JsonProperty("tool_calls") List<ToolCallDelta> toolCalls
	) {
		public static CompletionDelta content(String text) {
			return CompletionDelta.builder().content(text).build();
		}

		public static CompletionDelta reasoning(String text) {
			return CompletionDelta.builder().reasoningContent(text).build();
		}

		public static CompletionDelta toolCall(ToolCallDelta toolCall) {
			return CompletionDelta.builder()
			                      .toolCalls(List.of(toolCall))
			                      .build();
		}

		public static CompletionDelta empty() {
			return CompletionDelta.builder().build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallDelta(
			int index, String id, String type, ToolCallFunction function
	) {
		public static ToolCallDelta of(
				String id,
				String name,
				String arguments
		) {
			return ToolCallDelta.builder()
			                    .index(0)
			                    .id(id)
			                    .type("function")
			                    .function(ToolCallFunction.builder()
			                                              .name(name)
			                                              .arguments(arguments)
			                                              .build())
			                    .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallFunction(
			String name, String arguments
	) {}

	// --- Non-streaming records ---

	@Builder
	public record CompletionResponse(
			String id, String object, long created, String model,
			List<ResponseChoice> choices, Usage usage
	) {
		public static CompletionResponse of(
				String model,
				CompletionMessage message,
				String finishReason
		) {
			return CompletionResponse.builder()
			                         .id("chatcmpl-" + UUID.randomUUID())
			                         .object("chat.completion")
			                         .created(System.currentTimeMillis() / 1000)
			                         .model(model)
			                         .choices(
					                         List.of(
							                         ResponseChoice.of(
									                         message,
									                         finishReason
							                         )
					                         )
			                         )
			                         .usage(Usage.zero())
			                         .build();
		}
	}

	@Builder
	public record ResponseChoice(
			int index, CompletionMessage message,
			@JsonProperty("finish_reason") String finishReason
	) {
		public static ResponseChoice of(
				CompletionMessage message,
				String finishReason
		) {
			return ResponseChoice.builder()
			                     .index(0)
			                     .message(message)
			                     .finishReason(finishReason)
			                     .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CompletionMessage(
			String role, String content,
			@JsonProperty("reasoning_content") String reasoningContent,
			@JsonProperty("tool_calls") List<ToolCallDelta> toolCalls
	) {
		public static CompletionMessage of(
				String content,
				String reasoning,
				List<ToolCallDelta> toolCalls
		) {
			List<ToolCallDelta> tools;

			if (toolCalls != null && !toolCalls.isEmpty()) {
				tools = toolCalls;
			} else {
				tools = null;
			}

			return CompletionMessage.builder()
			                        .role("assistant")
			                        .content(content)
			                        .reasoningContent(reasoning)
			                        .toolCalls(tools)
			                        .build();
		}
	}

	@Builder
	public record Usage(
			@JsonProperty("prompt_tokens") int promptTokens,
			@JsonProperty("completion_tokens") int completionTokens,
			@JsonProperty("total_tokens") int totalTokens
	) {
		public static Usage zero() {
			return Usage.builder()
			            .promptTokens(0)
			            .completionTokens(0)
			            .totalTokens(0)
			            .build();
		}

		public static Usage of(int promptTokens, int completionTokens) {
			return Usage.builder().promptTokens(promptTokens)
			            .completionTokens(completionTokens)
			            .totalTokens(promptTokens + completionTokens)
			            .build();
		}
	}
}
