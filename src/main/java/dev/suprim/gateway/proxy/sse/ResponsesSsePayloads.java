package dev.suprim.gateway.proxy.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

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
}
