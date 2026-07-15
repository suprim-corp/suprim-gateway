package dev.suprim.gateway.proxy.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

public final class AnthropicSsePayloads {

	private AnthropicSsePayloads() {}

	@Builder
	public record MessageStart(
			String type,
			MessageInfo message
	) {
		public static MessageStart of(String id, String model) {
			return MessageStart.builder()
			                   .type("message_start")
			                   .message(MessageInfo.of(id, model))
			                   .build();
		}
	}

	@Builder
	public record MessageInfo(
			String id,
			String type,
			String role,
			List<Object> content,
			String model,
			Usage usage
	) {
		public static MessageInfo of(String id, String model) {
			return MessageInfo.builder()
			                  .id(id)
			                  .type("message")
			                  .role("assistant")
			                  .content(List.of())
			                  .model(model)
			                  .usage(Usage.zero())
			                  .build();
		}
	}

	@Builder
	public record Usage(
			@JsonProperty("input_tokens") int inputTokens,
			@JsonProperty("output_tokens") int outputTokens
	) {
		public static Usage zero() {
			return Usage.builder()
			            .inputTokens(0)
			            .outputTokens(0)
			            .build();
		}

		public static Usage of(int outputTokens) {
			return Usage.builder()
			            .inputTokens(0)
			            .outputTokens(outputTokens)
			            .build();
		}
	}

	@Builder
	public record ContentBlockStart(
			String type,
			int index,
			@JsonProperty("content_block") Object contentBlock
	) {
		public static ContentBlockStart thinking(int index) {
			return ContentBlockStart.builder()
			                        .type("content_block_start")
			                        .index(index)
			                        .contentBlock(
					                        ThinkingBlock.builder()
					                                     .type("thinking")
					                                     .thinking("")
					                                     .signature("")
					                                     .build()
			                        )
			                        .build();
		}

		public static ContentBlockStart text(int index) {
			return ContentBlockStart.builder()
			                        .type("content_block_start")
			                        .index(index)
			                        .contentBlock(
					                        TextBlock.builder()
					                                 .type("text")
					                                 .text("")
					                                 .build()
			                        )
			                        .build();
		}

		public static ContentBlockStart toolUse(
				int index,
				String id,
				String name
		) {
			return ContentBlockStart.builder()
			                        .type("content_block_start")
			                        .index(index)
			                        .contentBlock(
					                        ToolUseBlock.builder()
					                                    .type("tool_use")
					                                    .id(id)
					                                    .name(name)
					                                    .input(Map.of())
					                                    .build()
			                        )
			                        .build();
		}
	}

	@Builder
	public record ThinkingBlock(
			String type, String thinking, String signature
	) {}

	@Builder
	public record TextBlock(String type, String text) {}

	@Builder
	public record ToolUseBlock(
			String type, String id, String name, Map<String, Object> input
	) {}

	@Builder
	public record ContentBlockDelta(
			String type,
			int index,
			Object delta
	) {
		public static ContentBlockDelta thinking(int index, String text) {
			return ContentBlockDelta.builder()
			                        .type("content_block_delta")
			                        .index(index)
			                        .delta(
					                        ThinkingDelta.builder()
					                                     .type("thinking_delta")
					                                     .thinking(text)
					                                     .build()
			                        )
			                        .build();
		}

		public static ContentBlockDelta text(int index, String text) {
			return ContentBlockDelta.builder()
			                        .type("content_block_delta")
			                        .index(index)
			                        .delta(
					                        TextDelta.builder()
					                                 .type("text_delta")
					                                 .text(text)
					                                 .build()
			                        )
			                        .build();
		}

		public static ContentBlockDelta inputJson(
				int index,
				String partialJson
		) {
			return ContentBlockDelta.builder()
			                        .type("content_block_delta")
			                        .index(index)
			                        .delta(
					                        InputJsonDelta.builder()
					                                      .type("input_json_delta")
					                                      .partialJson(
							                                      partialJson
					                                      )
					                                      .build()
			                        )
			                        .build();
		}
	}

	@Builder
	public record ThinkingDelta(String type, String thinking) {}

	@Builder
	public record TextDelta(String type, String text) {}

	@Builder
	public record InputJsonDelta(
			String type, @JsonProperty("partial_json") String partialJson
	) {}

	@Builder
	public record ContentBlockStop(String type, int index) {
		public static ContentBlockStop of(int index) {
			return ContentBlockStop.builder()
			                       .type("content_block_stop")
			                       .index(index)
			                       .build();
		}
	}

	@Builder
	public record MessageDelta(
			String type,
			DeltaBody delta,
			Usage usage
	) {
		public static MessageDelta endTurn() {
			return MessageDelta.builder()
			                   .type("message_delta")
			                   .delta(
					                   DeltaBody.builder()
					                            .stopReason("end_turn")
					                            .build()
			                   )
			                   .usage(Usage.of(0))
			                   .build();
		}
	}

	@Builder
	public record DeltaBody(@JsonProperty("stop_reason") String stopReason) {}

	// --- Non-streaming records ---

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AnthropicResponse(
			String id,
			String type,
			String role,
			List<Object> content,
			String model,
			@JsonProperty("stop_reason") String stopReason,
			Usage usage
	) {
		public static AnthropicResponse of(
				String id,
				String model,
				List<Object> content,
				int inputTokens,
				int outputTokens
		) {
			return AnthropicResponse.builder()
			                        .id(id)
			                        .type("message")
			                        .role("assistant")
			                        .content(content)
			                        .model(model)
			                        .stopReason("end_turn")
			                        .usage(
					                        Usage.builder()
					                             .inputTokens(inputTokens)
					                             .outputTokens(outputTokens)
					                             .build()
			                        )
			                        .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ThinkingContentBlock(
			String type,
			String thinking
	) {
		public static ThinkingContentBlock of(String thinking) {
			return ThinkingContentBlock.builder()
			                           .type("thinking")
			                           .thinking(thinking)
			                           .build();
		}
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextContentBlock(
			String type,
			String text
	) {
		public static TextContentBlock of(String text) {
			return TextContentBlock.builder()
			                       .type("text")
			                       .text(text)
			                       .build();
		}
	}
}
