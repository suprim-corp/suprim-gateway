package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.StreamingEventWriter;
import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.utils.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Component
class CodexResponseRelay {

	private static final JsonMapper MAPPER = new JsonMapper();
	private final RequestLogPublisher logPublisher;

	@Builder
	record Call(
			String accountName,
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			long startTime,
			Format format,
			boolean requestThinkingEnabled,
			HttpServletResponse httpRes
	) {
		int latencyMs() {
			return (int) (System.currentTimeMillis() - startTime);
		}

		RequestLogEvent.RequestLogEventBuilder log(int status) {
			return RequestLogEvent.builder()
			                      .virtualKeyId(keyId)
			                      .accountId(accountName)
			                      .model(model)
			                      .requestedModel(model)
			                      .status(status)
			                      .latencyMs(latencyMs())
			                      .clientIp(clientIp);
		}
	}

	void handleError(CodexHttpClient.CodexResponse response, Call call) throws Exception {
		String body;
		try (InputStream input = response.body()) {
			body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		log.error(
				LogTag.CODEX + "Upstream {} body: {}", response.status(),
				body.length() > 500 ? body.substring(0, 500) : body
		);

		logPublisher.publish(
				call.log(response.status())
			    .promptTokens(call.inputTokens())
			    .streaming(false)
			    .errorMessage(body.length() > 200 ? body.substring(0, 200) : body)
			    .build()
		);
		ErrorResponse.openAi(
				call.httpRes(), response.status(), "Codex upstream error", "upstream_error"
		);
	}

	void relay(CodexHttpClient.CodexResponse response, Call call) throws Exception {
		if (call.format() == Format.RESPONSES) {
			passThrough(response, call);
			return;
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
			String firstData = readFirstData(reader);
			if (firstData == null) {
				handleEmptyStream(call);
				return;
			}

			HttpServletResponse httpRes = call.httpRes();
			httpRes.setCharacterEncoding("UTF-8");
			httpRes.setContentType("text/event-stream; charset=utf-8");
			httpRes.setHeader("Cache-Control", "no-cache");

			boolean thinkingEnabled = call.format() != Format.ANTHROPIC ||
			                          call.requestThinkingEnabled();
			StreamConverter converter = new StreamConverter();
			StreamingEventWriter eventWriter = null;
			Long firstTokenMs = null;
			int inputTokens = call.inputTokens();
			int outputTokens = 0;
			int upstreamEventCount = 0;
			int mappedEventCount = 0;
			String data = firstData;
			do {
				JsonNode node = MAPPER.readTree(data);
				String upstreamType = node.path("type").asString("unknown");
				upstreamEventCount++;
				Optional<String> failure = CodexSseMapper.failureMessage(node);
				if (failure.isPresent()) {
					log.error(LogTag.CODEX + "SSE stream failed: {}", failure.get());
					if (eventWriter == null && failure.get().startsWith("server_is_overloaded:")) {
						throw new ServerOverloadedException();
					}
					if (eventWriter == null) {
						handleStreamFailure(httpRes, call.format(), failure.get());
						return;
					}
					break;
				}
				Optional<KiroEvent> event = CodexSseMapper.toEvent(node);
				if (event.isPresent()) {
					mappedEventCount++;
					KiroEvent mappedEvent = event.get();
					if ("tool_use".equals(mappedEvent.type())) {
						log.debug(
								LogTag.CODEX + "Tool call: name={} callId={} argumentBytes={}",
								mappedEvent.toolName(), mappedEvent.toolUseId(),
								utf8Length(mappedEvent.toolInput())
						);
					} else {
						log.debug(
								LogTag.CODEX + "SSE event type={} mapped={}",
								upstreamType, mappedEvent.type()
						);
					}
					if (eventWriter == null) {
						eventWriter = new StreamingEventWriter(
								httpRes.getWriter(), converter, call.format(), call.model(),
								thinkingEnabled, inputTokens
						);
					}
					if (firstTokenMs == null) {
						firstTokenMs = System.currentTimeMillis() - call.startTime();
					}
					eventWriter.write(mappedEvent);
				}
				Optional<Integer> reportedOutput = CodexSseMapper.usageOutputTokens(node);
				if (reportedOutput.isPresent()) {
					outputTokens = reportedOutput.get();
				}
				Optional<Integer> reportedInput = CodexSseMapper.usageInputTokens(node);
				if (reportedInput.isPresent()) {
					inputTokens = reportedInput.get();
				}
			} while ((data = readFirstData(reader)) != null);

			if (eventWriter == null) {
				log.error(LogTag.CODEX + "SSE stream completed without usable output");
				handleStreamFailure(httpRes, call.format(), null);
				return;
			}
			log.info(
					LogTag.CODEX + "SSE summary: upstreamEvents={} mappedEvents={} hasOutput={} hasContent={} outputTokens={}",
					upstreamEventCount, mappedEventCount, eventWriter.hasOutput(),
					eventWriter.hasContent(), outputTokens
			);
			eventWriter.finish(outputTokens);
			logPublisher.publish(
					call.log(200)
					    .promptTokens(inputTokens)
					    .completionTokens(outputTokens)
					    .firstTokenMs(Optional.ofNullable(firstTokenMs)
					                          .map(Long::intValue)
					                          .orElse(null))
					    .streaming(true)
					    .build()
			);
		}
	}

	private void handleStreamFailure(
			HttpServletResponse httpRes,
			Format format,
			String failure
	) throws IOException {
		boolean contextLengthExceeded = failure != null &&
		                                failure.startsWith("context_length_exceeded:");
		int status = contextLengthExceeded ? 400 : 502;
		String message = contextLengthExceeded
		                 ? "Your input exceeds the context window of this model."
		                 : "Codex upstream stream failed";
		if (format == Format.ANTHROPIC) {
			ErrorResponse.anthropic(
					httpRes, status, message,
					contextLengthExceeded ? "invalid_request_error" : "api_error"
			);
		} else {
			ErrorResponse.openAi(
					httpRes, status, message,
					contextLengthExceeded ? "invalid_request_error" : "upstream_error"
			);
		}
	}

	private String readFirstData(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (!line.startsWith("data: ")) {
				continue;
			}
			String data = line.substring(6).trim();
			if (!data.isEmpty() && !"[DONE]".equals(data)) {
				return data;
			}
		}
		return null;
	}

	private String readFirstSseLine(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.startsWith("event:") || line.startsWith("data:")) {
				return line;
			}
		}
		return null;
	}

	private void handleEmptyStream(Call call) throws IOException {
		String message = "Codex upstream returned an empty SSE stream";
		log.error(LogTag.CODEX + "{}", message);
		logPublisher.publish(
				call.log(502)
			    .promptTokens(call.inputTokens())
			    .streaming(true)
			    .errorMessage(message)
			    .build()
		);
		ErrorResponse.openAi(
				call.httpRes(), 502, message, "upstream_empty_response"
		);
	}

	private void passThrough(CodexHttpClient.CodexResponse response, Call call) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
			String firstLine = readFirstSseLine(reader);
			if (firstLine == null) {
				handleEmptyStream(call);
				return;
			}

			HttpServletResponse httpRes = call.httpRes();
			httpRes.setCharacterEncoding("UTF-8");
			httpRes.setContentType("text/event-stream; charset=utf-8");
			httpRes.setHeader("Cache-Control", "no-cache");
			PrintWriter writer = httpRes.getWriter();
			long firstTokenMs = System.currentTimeMillis() - call.startTime();
			writer.write(firstLine);
			writer.write("\n");

			String line;
			while ((line = reader.readLine()) != null) {
				writer.write(line);
				writer.write("\n");
				if (line.isEmpty()) {
					writer.flush();
				}
			}
			writer.flush();
			logPublisher.publish(
					call.log(200)
					    .promptTokens(call.inputTokens())
					    .firstTokenMs((int) firstTokenMs)
					    .streaming(true)
					    .build()
			);
		}
	}

	private int utf8Length(Object value) {
		return value == null ? 0 : value.toString().getBytes(StandardCharsets.UTF_8).length;
	}

	static final class ServerOverloadedException extends Exception {
	}
}
