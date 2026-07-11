package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.ProxyFacade;
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

import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
@Component
public class AntigravityFacade {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final AntigravityAuthManager authManager;
	private final RequestLogPublisher logPublisher;

	public void handle(
			InternalRequest request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			ProxyFacade.Format format,
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
				projectId
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
			long startTime, HttpServletResponse httpRes
	) throws Exception {
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("text/event-stream; charset=utf-8");
		httpRes.setHeader("Cache-Control", "no-cache");
		PrintWriter writer = httpRes.getWriter();

		String id = "chatcmpl-" + UUID.randomUUID();
		int outputTokens = 0;
		Long firstTokenMs = null;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				response.body()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) continue;
				String data = line.substring(6).trim();
				if (data.isEmpty()) continue;

				String chunk = AntigravityStreamConverter.convertChunk(
						data,
						model,
						id
				);
				if (chunk != null) {
					if (firstTokenMs == null) {
						firstTokenMs = System.currentTimeMillis() - startTime;
					}
					writer.write(chunk);
					writer.flush();
					outputTokens++;
				}
			}
		}

		writer.write(AntigravityStreamConverter.buildStopChunk(model, id));
		writer.write(AntigravityStreamConverter.buildDoneEvent());
		writer.flush();

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(RequestLogEvent.builder()
		                                    .virtualKeyId(keyId)
		                                    .model(model)
		                                    .requestedModel(model)
		                                    .status(200)
		                                    .promptTokens(inputTokens)
		                                    .completionTokens(outputTokens >
		                                                      0 ? outputTokens : null)
		                                    .latencyMs(latency)
		                                    .firstTokenMs(firstTokenMs !=
		                                                  null ? firstTokenMs.intValue() : null)
		                                    .streaming(true)
		                                    .clientIp(clientIp)
		                                    .build());
	}

	private void handleNonStream(
			AntigravityHttpClient.AntigravityResponse response,
			String model, int inputTokens, String keyId, String clientIp,
			long startTime, HttpServletResponse httpRes
	) throws Exception {
		StringBuilder content = new StringBuilder();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				response.body()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) continue;
				String data = line.substring(6).trim();
				if (data.isEmpty()) continue;

				String chunk = AntigravityStreamConverter.convertChunk(
						data,
						model,
						"tmp"
				);
				if (chunk != null && chunk.contains("\"content\":")) {
					int start = chunk.indexOf("\"content\":\"") + 11;
					int end = chunk.indexOf("\"", start);
					if (start > 10 && end > start) {
						content.append(chunk, start, end);
					}
				}
			}
		}

		// For non-stream, rebuild as proper JSON response
		String text = content.toString();
		String id = "chatcmpl-" + UUID.randomUUID();
		int outputTokens = text.length() / 4;

		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");

		Map<String, Object> responseBody = Map.of(
				"id", id,
				"object", "chat.completion",
				"model", model,
				"choices", List.of(
						Map.of(
								"index",
								0,
								"message",
								Map.of("role", "assistant", "content", text),
								"finish_reason",
								"stop"
						)
				),
				"usage", Map.of(
						"prompt_tokens", inputTokens,
						"completion_tokens", outputTokens,
						"total_tokens", inputTokens + outputTokens
				)
		);
		MAPPER.writeValue(httpRes.getWriter(), responseBody);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(keyId)
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
}
