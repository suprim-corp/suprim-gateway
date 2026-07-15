package dev.suprim.gateway.proxy.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

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
			return new Usage(0, 0);
		}

		public static Usage of(int outputTokens) {
			return new Usage(0, outputTokens);
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
			                        .contentBlock(new ThinkingBlock(
					                        "thinking",
					                        "",
					                        ""
			                        ))
			                        .build();
		}

		public static ContentBlockStart text(int index) {
			return ContentBlockStart.builder()
			                        .type("content_block_start")
			                        .index(index)
			                        .contentBlock(new TextBlock("text", ""))
			                        .build();
		}
	}

	public record ThinkingBlock(
			String type, String thinking, String signature
	) {}

	public record TextBlock(String type, String text) {}

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
			                        .delta(new ThinkingDelta(
					                        "thinking_delta",
					                        text
			                        ))
			                        .build();
		}

		public static ContentBlockDelta text(int index, String text) {
			return ContentBlockDelta.builder()
			                        .type("content_block_delta")
			                        .index(index)
			                        .delta(new TextDelta("text_delta", text))
			                        .build();
		}
	}

	public record ThinkingDelta(String type, String thinking) {}

	public record TextDelta(String type, String text) {}

	@Builder
	public record ContentBlockStop(String type, int index) {
		public static ContentBlockStop of(int index) {
			return new ContentBlockStop("content_block_stop", index);
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
			                   .delta(new DeltaBody("end_turn"))
			                   .usage(Usage.of(0))
			                   .build();
		}
	}

	public record DeltaBody(@JsonProperty("stop_reason") String stopReason) {}
}
