package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.KiroEvent;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.utils.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
@Component
public class AntigravityFacade {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Map<String, String> THOUGHT_SIGNATURES = new ConcurrentHashMap<>();
	private final AntigravityAuthManager authManager;
	private final RequestLogPublisher logPublisher;
	private final StreamConverter streamConverter;

	public void handle(
			InternalRequest request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			Format format,
			HttpServletResponse httpRes
	) throws Exception {
		if (!authManager.isConnected()) {
			ErrorResponse.openAi(
					httpRes,
					401,
					"Antigravity provider not connected. Visit /auth/antigravity to connect.",
					"provider_not_connected"
			);
			return;
		}

		long startTime = System.currentTimeMillis();
		String accessToken = authManager.getAccessToken();
		String projectId = authManager.getProjectId();
		String payload = AntigravityPayloadBuilder.build(
				request,
				model,
				projectId,
				THOUGHT_SIGNATURES
		);

		log.debug(
				"[Antigravity] Calling {} with payload length {}",
				model,
				payload.length()
		);

		AntigravityHttpClient.AntigravityResponse response = AntigravityHttpClient.call(
				model,
				payload,
				accessToken
		);

		if (response.status() != 200) {
			handleError(
					response,
					model,
					inputTokens,
					keyId,
					clientIp,
					startTime,
					httpRes
			);
			return;
		}

		if (stream) {
			handleStream(
					response,
					model,
					inputTokens,
					keyId,
					clientIp,
					startTime,
					format,
					httpRes
			);
		} else {
			handleNonStream(
					response,
					model,
					inputTokens,
					keyId,
					clientIp,
					startTime,
					format,
					httpRes
			);
		}
	}

	private void handleError(
			AntigravityHttpClient.AntigravityResponse response,
			String model, int inputTokens, String keyId, String clientIp,
			long startTime, HttpServletResponse httpRes
	) throws Exception {
		String body;
		try (InputStream is = response.body()) {
			body = new String(is.readAllBytes());
		}
		log.error(
				"[Antigravity] Upstream {} body: {}", response.status(),
				body.length() > 500 ? body.substring(0, 500) : body
		);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(RequestLogEvent.builder()
		                                    .virtualKeyId(keyId)
		                                    .accountId(authManager.getDisplayName())
		                                    .model(model)
		                                    .requestedModel(model)
		                                    .status(response.status())
		                                    .promptTokens(inputTokens)
		                                    .latencyMs(latency)
		                                    .streaming(false)
		                                    .clientIp(clientIp)
		                                    .errorMessage(body.length() >
		                                                  200 ? body.substring(
				                                    0,
				                                    200
		                                    ) : body)
		                                    .build());

		ErrorResponse.openAi(
				httpRes,
				response.status(),
				"Antigravity upstream error",
				"upstream_error"
		);
	}

	private void handleStream(
			AntigravityHttpClient.AntigravityResponse response,
			String model, int inputTokens, String keyId, String clientIp,
			long startTime, Format format, HttpServletResponse httpRes
	) throws Exception {
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("text/event-stream; charset=utf-8");
		httpRes.setHeader("Cache-Control", "no-cache");
		PrintWriter writer = httpRes.getWriter();

		String id = generateId(format);
		int outputTokens = 0;
		Long firstTokenMs = null;
		boolean hasToolUse = false;
		int toolIndex = 0;
		StringBuilder fullContent = new StringBuilder();

		if (format == Format.ANTHROPIC) {
			writer.write(
					streamConverter.toAnthropicPreamble(
							id,
							model,
							inputTokens
					)
			);
			writer.flush();
		} else if (format == Format.RESPONSES) {
			writer.write(streamConverter.toResponsesCreated(id, model)
			             + streamConverter.toResponsesOutputItemAdded(id)
			             + streamConverter.toResponsesContentPartAdded());
			writer.flush();
		}

		try (
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(
								response.body()
						)
				)
		) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) {
					continue;
				}
				String data = line.substring(6).trim();
				if (data.isEmpty()) {
					continue;
				}

				AntigravityStreamConverter.ParsedChunk parsed =
						AntigravityStreamConverter.parseChunk(data);
				if (parsed == null) {
					continue;
				}

				if (parsed.text() != null && !parsed.text().isEmpty()) {
					if (firstTokenMs == null) {
						firstTokenMs = System.currentTimeMillis() - startTime;
					}
					fullContent.append(parsed.text());

					String chunk = switch (format) {
						case ANTHROPIC -> streamConverter.toAnthropicDelta(
								parsed.text()
						);
						case COMPLETION ->
								AntigravityStreamConverter.buildChunkPublic(
										id,
										model,
										parsed.text()
								);
						case RESPONSES -> streamConverter.toResponsesTextDelta(
								parsed.text()
						);
					};
					writer.write(chunk);
					writer.flush();
					outputTokens++;
				}

				if (parsed.functionCall() != null) {
					hasToolUse = true;
					if (firstTokenMs == null) {
						firstTokenMs = System.currentTimeMillis() - startTime;
					}
					toolIndex++;
					String toolCallId = parsed.functionCall().id() != null
							? parsed.functionCall().id()
							: "call_" + UUID.randomUUID().toString().replace(
							"-",
							""
					).substring(0, 20);
					if (parsed.thoughtSignature() != null) {
						THOUGHT_SIGNATURES.put(
								toolCallId,
								parsed.thoughtSignature()
						);
					}
					KiroEvent event = KiroEvent.toolUse(
							parsed.functionCall().name(),
							parsed.functionCall().args(),
							toolCallId
					);
					String chunk = switch (format) {
						case ANTHROPIC -> streamConverter.toAnthropicToolUse(
								event,
								toolIndex
						);
						case COMPLETION -> streamConverter.toOpenAiChunk(
								event,
								model,
								id
						);
						case RESPONSES -> streamConverter.toResponsesToolCall(
								event,
								toolIndex
						);
					};
					if (chunk != null) {
						writer.write(chunk);
						writer.flush();
					}
					outputTokens++;
				}
			}
		}

		String finale = switch (format) {
			case ANTHROPIC -> streamConverter.toAnthropicFinale(
					outputTokens,
					hasToolUse
			);
			case COMPLETION -> {
				if (hasToolUse) {
					yield AntigravityStreamConverter.buildDoneEvent();
				} else {
					yield AntigravityStreamConverter.buildStopChunk(model, id)
					      + AntigravityStreamConverter.buildDoneEvent();
				}
			}
			case RESPONSES -> {
				String text = fullContent.toString();
				if (hasToolUse) {
					yield streamConverter.toResponsesCompleted(
							id,
							model,
							text,
							List.of(),
							inputTokens,
							outputTokens
					);
				} else {
					yield streamConverter.toResponsesTextDone(text, id)
					      + streamConverter.toResponsesCompleted(
							id,
							model,
							text,
							List.of(),
							inputTokens,
							outputTokens
					);
				}
			}
		};
		writer.write(finale);
		writer.flush();

		log.debug(
				"[Antigravity] Stream done: textChunks={}, toolCalls={}, hasToolUse={}",
				outputTokens - toolIndex,
				toolIndex,
				hasToolUse
		);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(keyId)
				               .accountId(authManager.getDisplayName())
				               .model(model)
				               .requestedModel(model)
				               .status(200)
				               .promptTokens(inputTokens)
				               .completionTokens(
						               outputTokens >
						               0 ? outputTokens : null
				               )
				               .latencyMs(latency)
				               .firstTokenMs(
						               firstTokenMs !=
						               null ? firstTokenMs.intValue() : null
				               )
				               .streaming(true)
				               .clientIp(clientIp)
				               .build()
		);
	}

	private void handleNonStream(
			AntigravityHttpClient.AntigravityResponse response,
			String model, int inputTokens, String keyId, String clientIp,
			long startTime, Format format, HttpServletResponse httpRes
	) throws Exception {
		StringBuilder content = new StringBuilder();

		try (
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(
								response.body()
						)
				)
		) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) {
					continue;
				}
				String data = line.substring(6).trim();
				if (data.isEmpty()) {
					continue;
				}

				String text = AntigravityStreamConverter.extractText(data);
				if (text != null && !text.isEmpty()) {
					content.append(text);
				}
			}
		}

		String text = content.toString();
		String id = generateId(format);
		int outputTokens = text.length() / 4;

		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");

		Map<String, Object> responseBody = switch (format) {
			case ANTHROPIC -> streamConverter.toAnthropicNonStreaming(
					id, model, text, inputTokens, outputTokens
			);
			case COMPLETION -> streamConverter.toOpenAiNonStreaming(
					List.of(KiroEvent.content(text)),
					model
			);
			case RESPONSES -> streamConverter.toResponsesNonStreaming(
					id, model, text, inputTokens, outputTokens
			);
		};
		MAPPER.writeValue(httpRes.getWriter(), responseBody);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(keyId)
				               .accountId(authManager.getDisplayName())
				               .model(model)
				               .requestedModel(model)
				               .status(200)
				               .promptTokens(inputTokens)
				               .completionTokens(outputTokens >
				                                 0 ? outputTokens : null)
				               .latencyMs(latency)
				               .streaming(false)
				               .clientIp(clientIp)
				               .build()
		);
	}

	private String generateId(Format format) {
		return switch (format) {
			case ANTHROPIC -> "msg_" + UUID.randomUUID().toString().replace(
					"-",
					""
			).substring(0, 20);
			case RESPONSES -> "resp_" + UUID.randomUUID();
			default -> "chatcmpl-" + UUID.randomUUID();
		};
	}
}
