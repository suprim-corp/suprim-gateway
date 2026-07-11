package dev.suprim.gateway.proxy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class OpenAiRelayHandler {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final StreamConverter streamConverter;

	public record StreamResult(
			int promptTokens,
			int completionTokens,
			Long firstTokenMs
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
		String responseId = responsesFormat
				? "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
				: null;

		if (responsesFormat) {
			writer.write(streamConverter.toResponsesCreated(responseId, model));
			writer.write(streamConverter.toResponsesOutputItemAdded(responseId));
			writer.write(streamConverter.toResponsesContentPartAdded());
			writer.flush();
		}

		Integer promptTokens = null;
		Integer completionTokens = null;
		Long firstTokenMs = null;
		StringBuilder contentBuilder = new StringBuilder();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(upstream))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) continue;
				if (firstTokenMs == null) {
					firstTokenMs = System.currentTimeMillis() - startTime;
				}
				String data = line.substring(6).trim();
				if ("[DONE]".equals(data)) {
					if (!responsesFormat) {
						writer.write(line + "\n\n");
						writer.flush();
					}
					break;
				}

				log.debug("[Relay] chunk: {}", data.length() > 300 ? data.substring(0, 300) : data);

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
				} else {
					writer.write(line + "\n\n");
					writer.flush();
				}
			}
		}

		if (responsesFormat) {
			int outTokens = completionTokens != null ? completionTokens : 0;
			int inTokens = promptTokens != null ? promptTokens : inputTokens;
			log.debug("[Relay] stream done, content length={}, outTokens={}", contentBuilder.length(), outTokens);
			writer.write(streamConverter.toResponsesTextDone(contentBuilder.toString(), responseId));
			writer.write(streamConverter.toResponsesCompleted(
					responseId, model, contentBuilder.toString(),
					List.of(), inTokens, outTokens
			));
			writer.flush();
		}

		return new StreamResult(
				promptTokens != null ? promptTokens : inputTokens,
				completionTokens != null ? completionTokens : 0,
				firstTokenMs
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
			String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
			Object responsesBody = streamConverter.toResponsesNonStreaming(
					responseId, model, content, promptTokens, completionTokens
			);
			httpRes.getWriter().write(MAPPER.writeValueAsString(responsesBody));
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
			Integer prompt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
			Integer completion = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
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
}
