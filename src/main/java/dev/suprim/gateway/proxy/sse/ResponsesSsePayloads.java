package dev.suprim.gateway.proxy.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

public final class ResponsesSsePayloads {

	private ResponsesSsePayloads() {}

	private static final Map<String, String> emptySummary =
			Map.of(
					"type",
					"summary_text",
					"text",
					""
			);

	@Builder
	public record ReasoningSummaryPartAdded(
			String type,
			@JsonProperty("output_index") int outputIndex,
			@JsonProperty("summary_index") int summaryIndex,
			Map<String, String> part
	) {
		public static ReasoningSummaryPartAdded of(int summaryIndex) {
			return ReasoningSummaryPartAdded.builder()
			                                .type("response.reasoning_summary_part.added")
			                                .outputIndex(0)
			                                .summaryIndex(summaryIndex)
			                                .part(emptySummary)
			                                .build();
		}
	}

	@Builder
	public record ReasoningSummaryTextDelta(
			String type,
			@JsonProperty("output_index") int outputIndex,
			@JsonProperty("summary_index") int summaryIndex,
			String delta
	) {
		public static ReasoningSummaryTextDelta of(
				int summaryIndex,
				String delta
		) {
			return ReasoningSummaryTextDelta.builder()
			                                .type("response.reasoning_summary_text.delta")
			                                .outputIndex(0)
			                                .summaryIndex(summaryIndex)
			                                .delta(delta)
			                                .build();
		}
	}

	@Builder
	public record ReasoningSummaryPartDone(
			String type,
			@JsonProperty("output_index") int outputIndex,
			@JsonProperty("summary_index") int summaryIndex,
			Map<String, String> part
	) {
		public static ReasoningSummaryPartDone of(int summaryIndex) {
			return ReasoningSummaryPartDone.builder()
			                               .type("response.reasoning_summary_part.done")
			                               .outputIndex(0)
			                               .summaryIndex(summaryIndex)
			                               .part(emptySummary)
			                               .build();
		}
	}

	// --- Non-streaming records ---

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResponsesResponse(
			String id,
			String object,
			@JsonProperty("created_at") long createdAt,
			String status,
			String model,
			List<Object> output,
			ResponsesUsage usage
	) {
		public static ResponsesResponse of(
				String id,
				String model,
				List<Object> output,
				int inputTokens,
				int outputTokens
		) {
			return ResponsesResponse.builder()
			                        .id(id)
			                        .object("response")
			                        .createdAt(
					                        System.currentTimeMillis() / 1000)
			                        .status("completed")
			                        .model(model)
			                        .output(output)
			                        .usage(
					                        ResponsesUsage.of(
							                        inputTokens,
							                        outputTokens
					                        )
			                        )
			                        .build();
		}
	}

	@Builder
	public record ResponsesUsage(
			@JsonProperty("input_tokens") int inputTokens,
			@JsonProperty("output_tokens") int outputTokens,
			@JsonProperty("total_tokens") int totalTokens
	) {
		public static ResponsesUsage of(int inputTokens, int outputTokens) {
			return ResponsesUsage.builder()
			                     .inputTokens(inputTokens)
			                     .outputTokens(outputTokens)
			                     .totalTokens(inputTokens + outputTokens)
			                     .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReasoningOutputItem(
			String type,
			List<Map<String, String>> summary
	) {
		public static ReasoningOutputItem of(String reasoning) {
			return ReasoningOutputItem.builder()
			                          .type("reasoning")
			                          .summary(
					                          List.of(
							                          Map.of(
									                          "type",
									                          "summary_text",
									                          "text",
									                          reasoning
							                          )
					                          )
			                          )
			                          .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MessageOutputItem(
			String type,
			String role,
			List<OutputText> content
	) {
		public static MessageOutputItem of(String text) {
			return MessageOutputItem.builder()
			                        .type("message")
			                        .role("assistant")
			                        .content(List.of(OutputText.of(text)))
			                        .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record OutputText(
			String type,
			String text
	) {
		public static OutputText of(String text) {
			return OutputText.builder()
			                 .type("output_text")
			                 .text(text)
			                 .build();
		}
	}

	// --- Streaming records ---

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResponseCreated(
			String type,
			ResponseStub response
	) {
		public static ResponseCreated of(String responseId, String model) {
			return ResponseCreated.builder()
			                      .type("response.created")
			                      .response(
					                      ResponseStub.inProgress(
							                      responseId,
							                      model
					                      )
			                      )
			                      .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResponseStub(
			String id,
			String object,
			String status,
			String model,
			List<Object> output
	) {
		public static ResponseStub inProgress(String id, String model) {
			return ResponseStub.builder()
			                   .id(id)
			                   .object("response")
			                   .status("in_progress")
			                   .model(model)
			                   .output(List.of())
			                   .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record OutputItemAdded(
			String type,
			@JsonProperty("output_index") int outputIndex,
			Object item
	) {
		public static OutputItemAdded message(String msgId) {
			return OutputItemAdded.builder()
			                      .type("response.output_item.added")
			                      .outputIndex(0)
			                      .item(
					                      Map.of(
							                      "type", "message",
							                      "id", msgId,
							                      "role", "assistant",
							                      "content", List.of()
					                      )
			                      )
			                      .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ContentPartAdded(
			String type,
			@JsonProperty("output_index") int outputIndex,
			@JsonProperty("content_index") int contentIndex,
			Object part
	) {
		public static ContentPartAdded text() {
			return ContentPartAdded.builder()
			                       .type("response.content_part.added")
			                       .outputIndex(0)
			                       .contentIndex(0)
			                       .part(
					                       Map.of(
							                       "type",
							                       "output_text",
							                       "text",
							                       ""
					                       )
			                       )
			                       .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record OutputTextDelta(
			String type,
			@JsonProperty("output_index") int outputIndex,
			@JsonProperty("content_index") int contentIndex,
			String delta
	) {
		public static OutputTextDelta of(String delta) {
			return OutputTextDelta.builder()
			                      .type("response.output_text.delta")
			                      .outputIndex(0)
			                      .contentIndex(0)
			                      .delta(delta)
			                      .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record OutputTextDone(
			String type,
			@JsonProperty("output_index") int outputIndex,
			@JsonProperty("content_index") int contentIndex,
			String text
	) {
		public static OutputTextDone of(String text) {
			return OutputTextDone.builder()
			                     .type("response.output_text.done")
			                     .outputIndex(0)
			                     .contentIndex(0)
			                     .text(text)
			                     .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ContentPartDone(
			String type,
			@JsonProperty("output_index") int outputIndex,
			@JsonProperty("content_index") int contentIndex,
			Object part
	) {
		public static ContentPartDone text(String fullText) {
			return ContentPartDone.builder()
			                      .type("response.content_part.done")
			                      .outputIndex(0)
			                      .contentIndex(0)
			                      .part(
					                      Map.of(
							                      "type",
							                      "output_text",
							                      "text",
							                      fullText
					                      )
			                      )
			                      .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record OutputItemDone(
			String type,
			@JsonProperty("output_index") int outputIndex,
			Object item
	) {
		public static OutputItemDone message(String msgId, String fullText) {
			return OutputItemDone.builder()
			                     .type("response.output_item.done")
			                     .outputIndex(0)
			                     .item(
					                     Map.of(
							                     "type", "message",
							                     "id", msgId,
							                     "role", "assistant",
							                     "content", List.of(
									                     Map.of(
											                     "type",
											                     "output_text",
											                     "text",
											                     fullText
									                     )
							                     )
					                     )
			                     )
			                     .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResponseCompleted(
			String type,
			Object response
	) {
		public static ResponseCompleted of(
				String responseId, String model, List<Object> output,
				int inputTokens, int outputTokens
		) {
			return ResponseCompleted.builder()
			                        .type("response.completed")
			                        .response(
					                        Map.of(
							                        "id", responseId,
							                        "object", "response",
							                        "status", "completed",
							                        "model", model,
							                        "output", output,
							                        "usage", Map.of(
									                        "input_tokens",
									                        inputTokens,
									                        "output_tokens",
									                        outputTokens,
									                        "total_tokens",
									                        inputTokens +
									                        outputTokens
							                        )
					                        )
			                        )
			                        .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FunctionCallOutputItemAdded(
			String type,
			@JsonProperty("output_index") int outputIndex,
			Object item
	) {
		public static FunctionCallOutputItemAdded of(
				int outputIndex,
				Object item
		) {
			return FunctionCallOutputItemAdded.builder()
			                                  .type("response.output_item.added")
			                                  .outputIndex(outputIndex)
			                                  .item(item)
			                                  .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FunctionCallArgumentsDelta(
			String type,
			@JsonProperty("item_id") String itemId,
			@JsonProperty("call_id") String callId,
			@JsonProperty("output_index") int outputIndex,
			String delta
	) {
		public static FunctionCallArgumentsDelta of(
				String itemId, String callId, int outputIndex, String delta
		) {
			return FunctionCallArgumentsDelta.builder()
			                                 .type("response.function_call_arguments.delta")
			                                 .itemId(itemId)
			                                 .callId(callId)
			                                 .outputIndex(outputIndex)
			                                 .delta(delta)
			                                 .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FunctionCallArgumentsDone(
			String type,
			@JsonProperty("item_id") String itemId,
			@JsonProperty("call_id") String callId,
			@JsonProperty("output_index") int outputIndex,
			String arguments
	) {
		public static FunctionCallArgumentsDone of(
				String itemId, String callId, int outputIndex, String arguments
		) {
			return FunctionCallArgumentsDone.builder()
			                                .type("response.function_call_arguments.done")
			                                .itemId(itemId)
			                                .callId(callId)
			                                .outputIndex(outputIndex)
			                                .arguments(arguments)
			                                .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FunctionCallOutputItemDone(
			String type,
			@JsonProperty("output_index") int outputIndex,
			Object item
	) {
		public static FunctionCallOutputItemDone of(
				int outputIndex,
				Object item
		) {
			return FunctionCallOutputItemDone.builder()
			                                 .type("response.output_item.done")
			                                 .outputIndex(outputIndex)
			                                 .item(item)
			                                 .build();
		}
	}
}
