package dev.suprim.gateway.proxy;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StreamConverter {

	private final ObjectMapper mapper = new ObjectMapper();

	public String toOpenAiChunk(
			KiroEvent event,
			String model,
			String id
	) throws Exception {
		if ("content".equals(event.type())) {
			Map<String, Object> chunk = Map.of(
					"id", id,
					"object", "chat.completion.chunk",
					"created", System.currentTimeMillis() / 1000,
					"model", model,
					"choices", List.of(Map.of(
							"index", 0,
							"delta", Map.of("content", event.content()),
							"finish_reason", ""
					))
			);
			return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
		}

		if ("tool_use".equals(event.type()) && event.toolStop()) {
			String toolId = event.toolUseId() != null ? event.toolUseId() :
					"call_" + UUID.randomUUID();
			Map<String, Object> chunk = Map.of(
					"id", id,
					"object", "chat.completion.chunk",
					"created", System.currentTimeMillis() / 1000,
					"model", model,
					"choices", List.of(Map.of(
							"index", 0,
							"delta", Map.of(
									"tool_calls", List.of(Map.of(
											"index",
											0,
											"id",
											toolId,
											"type",
											"function",
											"function",
											Map.of(
													"name",
													event.toolName(),
													"arguments",
													event.toolInput()
											)
									))
							),
							"finish_reason", ""
					))
			);
			return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
		}

		return null;
	}

	public String toOpenAiDone() {
		return "data: [DONE]\n\n";
	}

	public String toOpenAiStopChunk(String model, String id) throws Exception {
		Map<String, Object> chunk = Map.of(
				"id", id,
				"object", "chat.completion.chunk",
				"created", System.currentTimeMillis() / 1000,
				"model", model,
				"choices", List.of(Map.of(
						"index", 0,
						"delta", Map.of(),
						"finish_reason", "stop"
				))
		);
		return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
	}

	public Map<String, Object> toOpenAiNonStreaming(
			List<KiroEvent> events,
			String model
	) {
		StringBuilder contentBuilder = new StringBuilder();
		List<Map<String, Object>> toolCalls = new ArrayList<>();

		for (KiroEvent event : events) {
			if ("content".equals(event.type()) && event.content() != null) {
				contentBuilder.append(event.content());
			} else if ("tool_use".equals(event.type()) && event.toolStop()) {
				String toolId = event.toolUseId() != null ? event.toolUseId() :
						"call_" + UUID.randomUUID();
				toolCalls.add(Map.of(
						"id",
						toolId,
						"type",
						"function",
						"function",
						Map.of(
								"name",
								event.toolName(),
								"arguments",
								event.toolInput()
						)
				));
			}
		}

		HashMap<String, Object> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", contentBuilder.toString());
		if (!toolCalls.isEmpty()) message.put("tool_calls", toolCalls);

		String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";

		return Map.of(
				"id",
				"chatcmpl-" + UUID.randomUUID(),
				"object",
				"chat.completion",
				"created",
				System.currentTimeMillis() / 1000,
				"model",
				model,
				"choices",
				List.of(Map.of(
						"index", 0,
						"message", message,
						"finish_reason", finishReason
				)),
				"usage",
				Map.of(
						"prompt_tokens",
						0,
						"completion_tokens",
						0,
						"total_tokens",
						0
				)
		);
	}

	String toAnthropicEvent(String eventType, Object data) throws Exception {
		return "event: " + eventType + "\ndata: " + mapper.writeValueAsString(
				data) + "\n\n";
	}

	public String toAnthropicPreamble(
			String id,
			String model,
			int inputTokens
	) throws Exception {
		return toAnthropicEvent(
				"message_start", Map.of(
						"type",
						"message_start",
						"message",
						Map.of(
								"id",
								id,
								"type",
								"message",
								"role",
								"assistant",
								"content",
								List.of(),
								"model",
								model,
								"usage",
								Map.of(
										"input_tokens",
										inputTokens,
										"output_tokens",
										0
								)
						)
				)
		)
		       + toAnthropicEvent(
				"content_block_start", Map.of(
						"type",
						"content_block_start",
						"index",
						0,
						"content_block",
						Map.of("type", "text", "text", "")
				)
		);
	}

	public String toAnthropicDelta(String text) throws Exception {
		return toAnthropicEvent(
				"content_block_delta", Map.of(
						"type", "content_block_delta", "index", 0,
						"delta", Map.of("type", "text_delta", "text", text)
				)
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
				"content_block_start", Map.of(
						"type", "content_block_start",
						"index", index,
						"content_block", Map.of(
								"type", "tool_use",
								"id", toolId,
								"name", event.toolName(),
								"input", Map.of()
						)
				)
		)
		       + toAnthropicEvent(
				"content_block_delta", Map.of(
						"type",
						"content_block_delta",
						"index",
						index,
						"delta",
						Map.of(
								"type",
								"input_json_delta",
								"partial_json",
								input
						)
				)
		)
		       + toAnthropicEvent(
				"content_block_stop", Map.of(
						"type", "content_block_stop",
						"index", index
				)
		);
	}

	public String toAnthropicFinale(int outputTokens) throws Exception {
		return toAnthropicEvent(
				"content_block_stop",
				Map.of("type", "content_block_stop", "index", 0)
		)
		       + toAnthropicEvent(
				"message_delta", Map.of(
						"type",
						"message_delta",
						"delta",
						Map.of("stop_reason", "end_turn"),
						"usage",
						Map.of("output_tokens", outputTokens)
				)
		)
		       + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
	}

	public Map<String, Object> toAnthropicNonStreaming(
			String id,
			String model,
			String content,
			int inputTokens,
			int outputTokens
	) {
		return Map.of(
				"id",
				id,
				"type",
				"message",
				"role",
				"assistant",
				"content",
				List.of(Map.of("type", "text", "text", content)),
				"model",
				model,
				"stop_reason",
				"end_turn",
				"usage",
				Map.of(
						"input_tokens",
						inputTokens,
						"output_tokens",
						outputTokens
				)
		);
	}

	public Map<String, Object> toResponsesNonStreaming(
			String id,
			String model,
			String content,
			int inputTokens,
			int outputTokens
	) {
		return Map.of(
				"id",
				id,
				"object",
				"response",
				"created_at",
				System.currentTimeMillis() / 1000,
				"status",
				"completed",
				"model",
				model,
				"output",
				List.of(
						Map.of(
								"type",
								"message",
								"role",
								"assistant",
								"content",
								List.of(
										Map.of(
												"type",
												"output_text",
												"text",
												content
										)
								)
						)
				),
				"usage",
				Map.of(
						"input_tokens",
						inputTokens,
						"output_tokens",
						outputTokens,
						"total_tokens",
						inputTokens + outputTokens
				)
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
				Map.of(
						"type",
						"response.created",
						"response",
						Map.of(
								"id",
								responseId,
								"object",
								"response",
								"status",
								"in_progress",
								"model",
								model,
								"output",
								List.of()
						)
				)
		);
	}

	public String toResponsesOutputItemAdded(String responseId) throws Exception {
		String msgId = "msg_" + responseId.substring(5);
		return toResponsesSse(
				Map.of(
						"type",
						"response.output_item.added",
						"output_index",
						0,
						"item",
						Map.of(
								"type",
								"message",
								"id",
								msgId,
								"role",
								"assistant",
								"content",
								List.of()
						)
				)
		);
	}

	public String toResponsesContentPartAdded() throws Exception {
		return toResponsesSse(
				Map.of(
						"type", "response.content_part.added",
						"output_index", 0,
						"content_index", 0,
						"part", Map.of("type", "output_text", "text", "")
				)
		);
	}

	public String toResponsesTextDelta(String delta) throws Exception {
		return toResponsesSse(
				Map.of(
						"type", "response.output_text.delta",
						"output_index", 0,
						"content_index", 0,
						"delta", delta
				)
		);
	}

	public String toResponsesTextDone(
			String fullText,
			String responseId
	) throws Exception {
		String msgId = "msg_" + responseId.substring(5);
		return toResponsesSse(
				Map.of(
						"type",
						"response.output_text.done",
						"output_index",
						0,
						"content_index",
						0,
						"text",
						fullText
				)
		)
		       + toResponsesSse(
				Map.of(
						"type",
						"response.content_part.done",
						"output_index",
						0,
						"content_index",
						0,
						"part",
						Map.of("type", "output_text", "text", fullText)
				)
		)
		       + toResponsesSse(
				Map.of(
						"type",
						"response.output_item.done",
						"output_index",
						0,
						"item",
						Map.of(
								"type",
								"message",
								"id",
								msgId,
								"role",
								"assistant",
								"content",
								List.of(Map.of(
										"type",
										"output_text",
										"text",
										fullText
								))
						)
				)
		);
	}

	public String toResponsesToolCall(
			KiroEvent event,
			int outputIndex
	) throws Exception {
		String callId = event.toolUseId() !=
		                null ? event.toolUseId() : UUID.randomUUID()
		                                               .toString()
		                                               .substring(0, 8);
		String fcId = "fc_" + callId;
		String name = event.toolName() != null ? event.toolName() : "unknown";
		String args = event.toolInput() != null ? event.toolInput() : "{}";
		return toResponsesSse(
				Map.of(
						"type",
						"response.output_item.added",
						"output_index",
						outputIndex,
						"item",
						Map.of(
								"type",
								"function_call",
								"id",
								fcId,
								"name",
								name,
								"arguments",
								"",
								"call_id",
								callId
						)
				)
		)
		       + toResponsesSse(
				Map.of(
						"type",
						"response.function_call_arguments.delta",
						"item_id",
						fcId,
						"call_id",
						callId,
						"output_index",
						outputIndex,
						"delta",
						args
				)
		)
		       + toResponsesSse(
				Map.of(
						"type",
						"response.function_call_arguments.done",
						"item_id",
						fcId,
						"call_id",
						callId,
						"output_index",
						outputIndex,
						"arguments",
						args
				)
		)
		       + toResponsesSse(
				Map.of(
						"type",
						"response.output_item.done",
						"output_index",
						outputIndex,
						"item",
						Map.of(
								"type",
								"function_call",
								"id",
								fcId,
								"name",
								name,
								"arguments",
								args,
								"call_id",
								callId
						)
				)
		);
	}

	public String toResponsesCompleted(
			String responseId,
			String model,
			String fullText,
			List<Map<String, Object>> toolCalls,
			int inputTokens,
			int outputTokens
	) throws Exception {
		List<Object> output = new ArrayList<>();
		output.add(
				Map.of(
						"type",
						"message",
						"role",
						"assistant",
						"content",
						List.of(Map.of("type", "output_text", "text", fullText))
				)
		);
		output.addAll(toolCalls);
		return toResponsesSse(
				Map.of(
						"type", "response.completed",
						"response", Map.of(
								"id",
								responseId,
								"object",
								"response",
								"status",
								"completed",
								"model",
								model,
								"output",
								output,
								"usage",
								Map.of(
										"input_tokens",
										inputTokens,
										"output_tokens",
										outputTokens,
										"total_tokens",
										inputTokens + outputTokens
								)
						)
				)
		);
	}
}
