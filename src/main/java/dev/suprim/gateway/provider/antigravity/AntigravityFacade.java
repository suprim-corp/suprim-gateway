package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroEvent;
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

import tools.jackson.databind.json.JsonMapper;

@Slf4j
@RequiredArgsConstructor
@Component
public class AntigravityFacade {

	private static final JsonMapper MAPPER = new JsonMapper();
	private static final Map<String, String> THOUGHT_SIGNATURES = new ConcurrentHashMap<>();
	private final AntigravityAuthManager authManager;
	private final RequestLogPublisher logPublisher;
	private final StreamConverter streamConverter;
	private final AccountRotator accountRotator;
	private final CredentialStore credentialStore;

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
		List<StoredAccount> accounts = credentialStore.findAllByProvider(
				Provider.ANTIGRAVITY.name()
		);
		if (accounts.isEmpty()) {
			ErrorResponse.openAi(
					httpRes,
					401,
					"Antigravity provider not connected. Visit /auth/antigravity to connect.",
					"provider_not_connected"
			);
			return;
		}

		long startTime = System.currentTimeMillis();
		int maxAttempts = accounts.size();

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			StoredAccount account = accountRotator.next(Provider.ANTIGRAVITY.name());
			String accessToken;
			try {
				accessToken = authManager.getAccessToken(account);
			} catch (Exception e) {
				log.error(
						LogTag.ANTIGRAVITY + "Auth failed for {}: {}",
						account.name(),
						e.getMessage()
				);
				continue;
			}
			String projectId = authManager.getProjectId(account);

			log.info(
					LogTag.ANTIGRAVITY + "Using account: {} (attempt {}/{})",
					account.name(), attempt + 1, maxAttempts
			);

			String payload = AntigravityPayloadBuilder.build(
					request, model, projectId, THOUGHT_SIGNATURES
			);

			AntigravityHttpClient.AntigravityResponse response = AntigravityHttpClient.call(
					model, payload, accessToken
			);

			if (response.status() == 429 || response.status() == 503) {
				log.warn(
						LogTag.ANTIGRAVITY + "Account {} got {}, trying next",
						account.name(), response.status()
				);
				try (InputStream is = response.body()) {
					is.readAllBytes();
				}
				continue;
			}

			if (response.status() != 200) {
				handleError(
						response, account.name(), model, inputTokens,
						keyId, clientIp, startTime, httpRes
				);
				return;
			}

			if (stream) {
				handleStream(
						response, account.name(), model, inputTokens,
						keyId, clientIp, startTime, format, httpRes
				);
			} else {
				handleNonStream(
						response, account.name(), model, inputTokens,
						keyId, clientIp, startTime, format, httpRes
				);
			}
			return;
		}

		ErrorResponse.openAi(
				httpRes,
				429,
				"All accounts rate-limited",
				"rate_limit_exhausted"
		);
	}

	private void handleError(
			AntigravityHttpClient.AntigravityResponse response,
			String accountName,
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			long startTime,
			HttpServletResponse httpRes
	) throws Exception {
		String body;
		try (InputStream is = response.body()) {
			body = new String(is.readAllBytes());
		}
		log.error(
				LogTag.ANTIGRAVITY + "Upstream {} body: {}", response.status(),
				body.length() > 500 ? body.substring(0, 500) : body
		);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(keyId)
				               .accountId(accountName)
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
				               .build()
		);

		ErrorResponse.openAi(
				httpRes,
				response.status(),
				"Antigravity upstream error",
				"upstream_error"
		);
	}

	private void handleStream(
			AntigravityHttpClient.AntigravityResponse response,
			String accountName,
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			long startTime,
			Format format,
			HttpServletResponse httpRes
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
			             + streamConverter.toResponsesContentPartAdded()
			);
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

					String toolCallId;

					if (parsed.functionCall().id() != null) {
						toolCallId = parsed.functionCall().id();
					} else {
						String randomId = UUID.randomUUID()
						                      .toString()
						                      .replace(
								                      "-",
								                      ""
						                      )
						                      .substring(0, 20);
						toolCallId = "call_" + randomId;
					}

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
				LogTag.ANTIGRAVITY + "Stream done: textChunks={}, toolCalls={}, hasToolUse={}",
				outputTokens - toolIndex,
				toolIndex,
				hasToolUse
		);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(keyId)
				               .accountId(accountName)
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
			String accountName,
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			long startTime,
			Format format,
			HttpServletResponse httpRes
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
				               .accountId(accountName)
				               .model(model)
				               .requestedModel(model)
				               .status(200)
				               .promptTokens(inputTokens)
				               .completionTokens(
						               outputTokens >
						               0 ? outputTokens : null
				               )
				               .latencyMs(latency)
				               .streaming(false)
				               .clientIp(clientIp)
				               .build()
		);
	}

	private String generateId(Format format) {
		return switch (format) {
			case ANTHROPIC -> "msg_" + UUID.randomUUID()
			                               .toString()
			                               .replace(
					                               "-",
					                               ""
			                               )
			                               .substring(0, 20);
			case RESPONSES -> "resp_" + UUID.randomUUID();
			default -> "chatcmpl-" + UUID.randomUUID();
		};
	}
}
