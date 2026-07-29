package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.logging.ProviderOutcome;
import dev.suprim.gateway.logging.RequestLogCall;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.SseHeartbeat;
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
	private final SseHeartbeat sseHeartbeat;

	@Builder
	record Call(
			String accountName,
			int inputTokens,
			boolean requestThinkingEnabled,
			HttpServletResponse httpRes,
			RequestLogCall requestLogCall
	) {
		String model() {
			return requestLogCall.model();
		}

		Format format() {
			return requestLogCall.format();
		}

		long startTime() {
			return requestLogCall.startedAt();
		}
	}

	ProviderOutcome handleError(
			CodexHttpClient.CodexResponse response,
			Call call
	) throws Exception {
		String body;
		try (InputStream input = response.body()) {
			body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		log.error(
				LogTag.CODEX + "Upstream {} body: {}", response.status(),
				body.length() > 500 ? body.substring(0, 500) : body
		);

		ErrorResponse.openAi(
				call.httpRes(),
				response.status(),
				"Codex upstream error",
				"upstream_error"
		);
		return call.requestLogCall().upstreamError(
				call.accountName(), response.status(), body
		);
	}

	ProviderOutcome relay(
			CodexHttpClient.CodexResponse response,
			Call call
	) throws Exception {
		if (call.format() == Format.RESPONSES) {
			return passThrough(response, call);
		}

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(response.body())
		)) {
			String firstData = readFirstData(reader);
			if (firstData == null) {
				return handleEmptyStream(call);
			}

			try (SseHeartbeat.Session session = sseHeartbeat.open(call.httpRes())) {
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
					Optional<String> failure = CodexSseMapper.failureMessage(
							node);
					if (failure.isPresent()) {
						log.error(
								LogTag.CODEX + "SSE stream failed: {}",
								failure.get()
						);
						if (eventWriter == null && failure.get().startsWith(
								"server_is_overloaded:")) {
							throw new ServerOverloadedException();
						}
						if (eventWriter == null) {
							return handleStreamFailure(call, failure.get());
						}
						break;
					}
					Optional<KiroEvent> event = CodexSseMapper.toEvent(node);
					if (event.isPresent()) {
						mappedEventCount++;
						KiroEvent mappedEvent = event.get();
						if ("tool_use".equals(mappedEvent.type())) {
							log.debug(
									LogTag.CODEX +
									"Tool call: name={} callId={} argumentBytes={}",
									mappedEvent.toolName(),
									mappedEvent.toolUseId(),
									utf8Length(mappedEvent.toolInput())
							);
						} else {
							log.debug(
									LogTag.CODEX +
									"SSE event type={} mapped={}",
									upstreamType, mappedEvent.type()
							);
						}
						if (eventWriter == null) {
							eventWriter = new StreamingEventWriter(
									session.writer(),
									converter,
									call.format(),
									call.model(),
									thinkingEnabled,
									inputTokens
							);
						}
						if (firstTokenMs == null) {
							firstTokenMs =
									System.currentTimeMillis() -
									call.startTime();
						}
						eventWriter.write(mappedEvent);
					}
					Optional<Integer> reportedOutput =
							CodexSseMapper.usageOutputTokens(node);
					if (reportedOutput.isPresent()) {
						outputTokens = reportedOutput.get();
					}
					Optional<Integer> reportedInput =
							CodexSseMapper.usageInputTokens(node);
					if (reportedInput.isPresent()) {
						inputTokens = reportedInput.get();
					}
				} while ((data = readFirstData(reader)) != null);

				if (eventWriter == null) {
					log.error(
							LogTag.CODEX +
							"SSE stream completed without usable output"
					);
					return handleStreamFailure(call, null);
				}
				log.info(
						LogTag.CODEX +
						"SSE summary: upstreamEvents={} mappedEvents={} hasOutput={} hasContent={} outputTokens={}",
						upstreamEventCount,
						mappedEventCount,
						eventWriter.hasOutput(),
						eventWriter.hasContent(),
						outputTokens
				);
				eventWriter.finish(outputTokens);
				return call.requestLogCall().success(
						call.accountName(),
						inputTokens,
						outputTokens,
						firstTokenMs,
						null
				);
			}
		}

	}

	private ProviderOutcome handleStreamFailure(
			Call call,
			String failure
	) throws IOException {
		boolean contextLengthExceeded = failure != null &&
		                                failure.startsWith(
				                                "context_length_exceeded:"
		                                );
		int status = contextLengthExceeded ? 400 : 502;
		String message = contextLengthExceeded
				? "Your input exceeds the context window of this model."
				: "Codex upstream stream failed";
		if (call.format() == Format.ANTHROPIC) {
			ErrorResponse.anthropic(
					call.httpRes(), status, message,
					contextLengthExceeded ? "invalid_request_error" : "api_error"
			);
		} else {
			ErrorResponse.openAi(
					call.httpRes(), status, message,
					contextLengthExceeded ? "invalid_request_error" : "upstream_error"
			);
		}
		return call.requestLogCall().upstreamError(
				call.accountName(),
				status,
				message
		);
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

	private ProviderOutcome handleEmptyStream(Call call) throws IOException {
		String message = "Codex upstream returned an empty SSE stream";
		log.error(LogTag.CODEX + "{}", message);
		ErrorResponse.openAi(
				call.httpRes(), 502, message, "upstream_empty_response"
		);
		return call.requestLogCall().upstreamError(
				call.accountName(),
				502,
				message
		);
	}

	private ProviderOutcome passThrough(
			CodexHttpClient.CodexResponse response,
			Call call
	) throws Exception {
		try (
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(response.body())
				)
		) {
			String firstLine = readFirstSseLine(reader);
			if (firstLine == null) {
				return handleEmptyStream(call);
			}

			try (SseHeartbeat.Session session = sseHeartbeat.open(
					call.httpRes(), call.requestLogCall().streaming()
			)) {
				PrintWriter writer = session.writer();
				long firstTokenMs =
						System.currentTimeMillis() - call.startTime();
				StringBuilder event = new StringBuilder(firstLine).append('\n');
				String line;
				while ((line = reader.readLine()) != null) {
					event.append(line).append('\n');
					if (line.isEmpty()) {
						writer.write(event.toString());
						writer.flush();
						event.setLength(0);
					}
				}
				if (!event.isEmpty()) {
					writer.write(event.toString());
				}
				writer.flush();
				return call.requestLogCall().success(
						call.accountName(),
						call.inputTokens(),
						null,
						firstTokenMs,
						null
				);
			}
		}
	}

	private int utf8Length(Object value) {
		return value == null ? 0 : value.toString()
		                                .getBytes(StandardCharsets.UTF_8).length;
	}

	static final class ServerOverloadedException extends Exception {
	}
}
