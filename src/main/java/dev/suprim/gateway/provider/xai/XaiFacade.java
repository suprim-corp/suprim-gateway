package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.ProxyFacade;
import dev.suprim.gateway.utils.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class XaiFacade {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final XaiAuthManager authManager;
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
					"xAI provider not connected. Visit /auth/xai to connect.",
					"provider_not_connected"
			);
			return;
		}

		long startTime = System.currentTimeMillis();
		String accessToken = authManager.getAccessToken();

		ObjectNode payloadNode = MAPPER.valueToTree(request);
		if (stream && !payloadNode.has("stream_options")) {
			payloadNode.set("stream_options", MAPPER.valueToTree(Map.of("include_usage", true)));
		}

		String payload = MAPPER.writeValueAsString(payloadNode);

		log.debug("[xAI] Calling {} with payload length {}", model, payload.length());

		XaiHttpClient.XaiResponse response = XaiHttpClient.call(payload, accessToken);
		log.info("[xAI] Upstream responded with status {}", response.status());

		if (response.status() != 200) {
			handleError(response, model, inputTokens, keyId, clientIp, startTime, httpRes);
			return;
		}

		if (stream) {
			handleStream(response, model, inputTokens, keyId, clientIp, startTime, httpRes);
		} else {
			handleNonStream(response, model, inputTokens, keyId, clientIp, startTime, httpRes);
		}
	}

	private void handleError(
			XaiHttpClient.XaiResponse response,
			String model, int inputTokens, String keyId, String clientIp,
			long startTime, HttpServletResponse httpRes
	) throws Exception {
		String body;
		try (InputStream is = response.body()) {
			body = new String(is.readAllBytes());
		}
		log.error("[xAI] Upstream {} body: {}", response.status(),
				body.length() > 500 ? body.substring(0, 500) : body);

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
		                                    .errorMessage(body.length() > 200 ? body.substring(0, 200) : body)
		                                    .build());

		ErrorResponse.openAi(httpRes, response.status(), "xAI upstream error", "upstream_error");
	}

	private void handleStream(
			XaiHttpClient.XaiResponse response,
			String model, int inputTokens, String keyId, String clientIp,
			long startTime, HttpServletResponse httpRes
	) throws Exception {
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("text/event-stream; charset=utf-8");
		httpRes.setHeader("Cache-Control", "no-cache");
		PrintWriter writer = httpRes.getWriter();

		Integer promptTokens = null;
		Integer completionTokens = null;
		Long firstTokenMs = null;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("data: ")) {
					if (firstTokenMs == null) {
						firstTokenMs = System.currentTimeMillis() - startTime;
					}
					String data = line.substring(6).trim();
					if (!"[DONE]".equals(data)) {
						Integer[] usage = extractUsage(data);
						if (usage != null) {
							promptTokens = usage[0];
							completionTokens = usage[1];
						}
					}
					writer.write(line + "\n\n");
					writer.flush();
				}
			}
		}

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(RequestLogEvent.builder()
		                                    .virtualKeyId(keyId)
		                                    .model(model)
		                                    .requestedModel(model)
		                                    .status(200)
		                                    .promptTokens(promptTokens != null ? promptTokens : inputTokens)
		                                    .completionTokens(completionTokens)
		                                    .latencyMs(latency)
		                                    .firstTokenMs(firstTokenMs != null ? firstTokenMs.intValue() : null)
		                                    .streaming(true)
		                                    .clientIp(clientIp)
		                                    .build());
	}

	private void handleNonStream(
			XaiHttpClient.XaiResponse response,
			String model, int inputTokens, String keyId, String clientIp,
			long startTime, HttpServletResponse httpRes
	) throws Exception {
		String body;
		try (InputStream is = response.body()) {
			body = new String(is.readAllBytes());
		}

		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");
		httpRes.getWriter().write(body);
		httpRes.getWriter().flush();

		Integer promptTokens = null;
		Integer completionTokens = null;
		Integer[] usage = extractUsage(body);
		if (usage != null) {
			promptTokens = usage[0];
			completionTokens = usage[1];
		}

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(RequestLogEvent.builder()
		                                    .virtualKeyId(keyId)
		                                    .model(model)
		                                    .requestedModel(model)
		                                    .status(200)
		                                    .promptTokens(promptTokens != null ? promptTokens : inputTokens)
		                                    .completionTokens(completionTokens)
		                                    .latencyMs(latency)
		                                    .streaming(false)
		                                    .clientIp(clientIp)
		                                    .build());
	}

	private Integer[] extractUsage(String json) {
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
}
