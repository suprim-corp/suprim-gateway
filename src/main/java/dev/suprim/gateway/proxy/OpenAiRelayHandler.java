package dev.suprim.gateway.proxy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class OpenAiRelayHandler {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final StreamConverter streamConverter;

	public record StreamResult(
			int promptTokens,
			int completionTokens,
			Long firstTokenMs,
			boolean hasToolUse
	) {}

	public StreamResult relayStream(
			InputStream upstream,
			Format format,
			String model,
			int inputTokens,
			long startTime,
			HttpServletResponse httpRes
	) throws Exception {
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("text/event-stream; charset=utf-8");
		httpRes.setHeader("Cache-Control", "no-cache");
		PrintWriter writer = httpRes.getWriter();

		boolean responsesFormat = format == Format.RESPONSES;
		boolean anthropicFormat = format == Format.ANTHROPIC;
		String responseId = responsesFormat
				? "resp_" + UUID.randomUUID()
				                .toString()
				                .replace("-", "")
				                .substring(0, 24)
				: null;
		String msgId = anthropicFormat
				? "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)
				: null;

		if (responsesFormat) {
			writer.write(streamConverter.toResponsesCreated(responseId, model));
			writer.write(streamConverter.toResponsesOutputItemAdded(responseId));
			writer.write(streamConverter.toResponsesContentPartAdded());
			writer.flush();
		} else if (anthropicFormat) {
			writer.write(streamConverter.toAnthropicPreamble(msgId, model, inputTokens));
			writer.flush();
		}

		Integer promptTokens = null;
		Integer completionTokens = null;
		Long firstTokenMs = null;
		StringBuilder contentBuilder = new StringBuilder();
		List<ToolCallChunk> toolCalls = new ArrayList<>();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				upstream))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) continue;
				if (firstTokenMs == null) {
					firstTokenMs = System.currentTimeMillis() - startTime;
				}
				String data = line.substring(6).trim();
				if ("[DONE]".equals(data)) {
					if (!responsesFormat && !anthropicFormat) {
						writer.write(line + "\n\n");
						writer.flush();
					}
					break;
				}

				Integer[] usage = extractUsage(data);
				if (usage != null) {
					promptTokens = usage[0];
					completionTokens = usage[1];
				}

				if (responsesFormat) {
					String delta = extractContentDelta(data);
					if (delta != null && !delta.isEmpty()) {
						contentBuilder.append(delta);
						writer.write(streamConverter.toResponsesTextDelta(delta));
						writer.flush();
					}
					ToolCallChunk toolCall = extractToolCall(data);
					if (toolCall != null) {
						toolCalls.add(toolCall);
					}
				} else if (anthropicFormat) {
					String delta = extractContentDelta(data);
					if (delta != null && !delta.isEmpty()) {
						contentBuilder.append(delta);
						writer.write(streamConverter.toAnthropicDelta(delta));
						writer.flush();
					}
					ToolCallChunk toolCall = extractToolCall(data);
					if (toolCall != null) {
						toolCalls.add(toolCall);
					}
				} else {
					writer.write(line + "\n\n");
					writer.flush();
				}
			}
		}

		boolean hasToolUse = !toolCalls.isEmpty();

		if (responsesFormat) {
			int outTokens = completionTokens != null ? completionTokens : 0;
			int inTokens = promptTokens != null ? promptTokens : inputTokens;
			writer.write(
					streamConverter.toResponsesTextDone(
							contentBuilder.toString(),
							responseId
					)
			);

			int outputIndex = 1;
			List<Object> toolCallMaps = new ArrayList<>();
			for (ToolCallChunk tc : toolCalls) {
				KiroEvent toolEvent = KiroEvent.toolUse(
						tc.name(),
						tc.arguments(),
						tc.id()
				);
				String toolCallSse = streamConverter.toResponsesToolCall(
						toolEvent,
						outputIndex
				);
				writer.write(toolCallSse);
				toolCallMaps.add(tc.toResponsesOutput());
				outputIndex++;
			}

			writer.write(
					streamConverter.toResponsesCompleted(
							responseId, model, contentBuilder.toString(),
							toolCallMaps, inTokens, outTokens
					)
			);
			writer.flush();
		} else if (anthropicFormat) {
			int outTokens = completionTokens != null ? completionTokens : 0;

			int toolIndex = 1;
			for (ToolCallChunk tc : toolCalls) {
				KiroEvent toolEvent = KiroEvent.toolUse(
						tc.name(),
						tc.arguments(),
						tc.id()
				);
				writer.write(streamConverter.toAnthropicToolUse(toolEvent, toolIndex));
				toolIndex++;
			}

			writer.write(streamConverter.toAnthropicFinale(outTokens, hasToolUse));
			writer.flush();
		}

		return new StreamResult(
				promptTokens != null ? promptTokens : inputTokens,
				completionTokens != null ? completionTokens : 0,
				firstTokenMs,
				hasToolUse
		);
	}

	public void writeNonStream(
			String body,
			Format format,
			String model,
			int promptTokens,
			int completionTokens,
			HttpServletResponse httpRes
	) throws Exception {
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");

		if (format == Format.RESPONSES) {
			String content = extractFullContent(body);
			String responseId = "resp_" + UUID.randomUUID().toString().replace(
					"-",
					""
			).substring(0, 24);
			Object responsesBody = streamConverter.toResponsesNonStreaming(
					responseId, model, content, promptTokens, completionTokens
			);
			httpRes.getWriter().write(MAPPER.writeValueAsString(responsesBody));
		} else if (format == Format.ANTHROPIC) {
			String content = extractFullContent(body);
			String msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
			Object anthropicBody = streamConverter.toAnthropicNonStreaming(
					msgId, model, content, promptTokens, completionTokens
			);
			httpRes.getWriter().write(MAPPER.writeValueAsString(anthropicBody));
		} else {
			httpRes.getWriter().write(body);
		}
		httpRes.getWriter().flush();
	}

	public Integer[] extractUsage(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			JsonNode usage = node.get("usage");
			if (usage == null) return null;
			Integer prompt = usage.has("prompt_tokens") ? usage.get(
					"prompt_tokens").asInt() : null;
			Integer completion = usage.has("completion_tokens") ? usage.get(
					"completion_tokens").asInt() : null;
			if (prompt == null && completion == null) return null;
			return new Integer[]{prompt, completion};
		} catch (Exception e) {
			return null;
		}
	}

	private String extractContentDelta(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			JsonNode choices = node.get("choices");
			if (choices == null || choices.isEmpty()) return null;
			JsonNode delta = choices.get(0).get("delta");
			if (delta == null) return null;
			JsonNode content = delta.get("content");
			return content != null ? content.asString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private String extractFullContent(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			JsonNode choices = node.get("choices");
			if (choices == null || choices.isEmpty()) return "";
			JsonNode message = choices.get(0).get("message");
			if (message == null) return "";
			JsonNode content = message.get("content");
			return content != null ? content.asString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	record ToolCallChunk(String id, String name, String arguments) {
		StreamConverter.FunctionCallItem toResponsesOutput() {
			return StreamConverter.FunctionCallItem.of("fc_" + id, id, name, arguments);
		}
	}

	private ToolCallChunk extractToolCall(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			JsonNode choices = node.get("choices");
			if (choices == null || choices.isEmpty()) return null;
			JsonNode delta = choices.get(0).get("delta");
			if (delta == null || !delta.has("tool_calls")) return null;
			JsonNode calls = delta.get("tool_calls");
			if (calls == null || calls.isEmpty()) return null;
			JsonNode call = calls.get(0);
			String id = call.has("id") ? call.get("id")
			                                 .asString() : UUID.randomUUID()
			                                                   .toString()
			                                                   .substring(0, 8);
			JsonNode fn = call.get("function");
			if (fn == null) return null;
			String name = fn.has("name") ? fn.get("name")
			                                 .asString() : "unknown";
			String args = fn.has("arguments") ? fn.get("arguments")
			                                      .asString() : "{}";
			return new ToolCallChunk(id, name, args);
		} catch (Exception e) {
			return null;
		}
	}
}
