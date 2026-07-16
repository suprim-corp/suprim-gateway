package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
import dev.suprim.gateway.proxy.ProxyChain;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
class AntigravityHttpClient {

	private static final long BASE_RETRY_DELAY = 1000;
	private static final int MAX_RETRIES = 3;

	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(15))
			          .build();

	@Builder
	record AntigravityResponse(int status, InputStream body) {}

	static String buildUrl() {
		return Antigravity.CLOUDCODE_BASE + "/v1internal:streamGenerateContent?alt=sse";
	}

	static Map<String, String> buildHeaders(String accessToken) {
		return Map.of(
				"Authorization", "Bearer " + accessToken,
				"Content-Type", "application/json",
				"User-Agent", Antigravity.USER_AGENT
		);
	}

	static List<Map<String, Object>> listModels(String accessToken, String projectId, ProxyChain proxyChain) throws IOException {
		String body = projectId != null && !projectId.isEmpty()
				? "{\"project\":\"" + projectId + "\"}"
				: "{}";
		HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
		                                            .uri(URI.create(Antigravity.CLOUDCODE_BASE + "/v1internal:fetchAvailableModels"));
		Map<String, String> headers = Map.of(
				"Authorization", "Bearer " + accessToken,
				"Content-Type", "application/json",
				"User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/2.0.1 Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36",
				"X-Goog-Api-Client", "google-cloud-sdk vscode/1.96.0",
				"Client-Metadata", "{\"ideType\":\"VSCODE\",\"platform\":\"MACOS\",\"pluginType\":\"GEMINI\",\"osVersion\":\"15.1\",\"arch\":\"arm64\"}"
		);
		headers.forEach(reqBuilder::header);
		reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
		try {
			HttpResponse<String> response = proxyChain.send(reqBuilder.build());
			if (response.statusCode() != 200) {
				log.warn("[Antigravity] listModels returned {}: {}", response.statusCode(), response.body());
				return List.of();
			}
			return parseModelsWithQuota(response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("listModels interrupted", e);
		}
	}

	private static List<Map<String, Object>> parseModelsWithQuota(String json) {
		List<Map<String, Object>> models = new ArrayList<>();
		try {
			JsonNode root = new ObjectMapper().readTree(json);
			JsonNode available;

			if (root.get("availableModels") != null) {
				available = root.get("availableModels");
			} else {
				available = root.get("models");
			}

			if (available == null) {
				return models;
			}

			if (available.isObject()) {
				for (Map.Entry<String, JsonNode> entry : available.properties()) {
					String key = entry.getKey();
					String modelId = key.startsWith("models/") ? key.substring(7) : key;
					if (modelId.isEmpty() || modelId.contains(" ")) continue;

					JsonNode value = entry.getValue();
					int quotaPct = -1;
					JsonNode quotaInfo = value.get("quotaInfo");
					if (quotaInfo != null && quotaInfo.has("remainingFraction")) {
						double fraction = quotaInfo.get("remainingFraction").asDouble();
						quotaPct = (int) Math.round(fraction * 100);
					}
					String displayName = value.has("displayName") ? value.get("displayName").asString() : null;
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("id", modelId);
					if (quotaPct >= 0) item.put("quota", quotaPct);
					if (displayName != null) {
						item.put("displayName", displayName);
					}
					models.add(item);
				}
			} else if (available.isArray()) {
				for (JsonNode arrayItem : available) {
					JsonNode model = arrayItem.get("model");
					if (model != null && model.has("name")) {
						String name = model.get("name").asString();
						String modelId = name.startsWith("models/") ? name.substring(7) : name;

						int quotaPct = -1;
						JsonNode quotaInfo = arrayItem.get("quotaInfo");
						if (quotaInfo != null && quotaInfo.has("remainingFraction")) {
							double fraction = quotaInfo.get("remainingFraction").asDouble();
							quotaPct = (int) Math.round(fraction * 100);
						}
						String displayName = model.has("displayName") ? model.get("displayName").asString() : null;
						Map<String, Object> item = new java.util.LinkedHashMap<>();
						item.put("id", modelId);
						if (quotaPct >= 0) item.put("quota", quotaPct);
						if (displayName != null) item.put("displayName", displayName);
						models.add(item);
					}
				}
			}
		} catch (Exception e) {
			log.warn("[Antigravity] Failed to parse models response: {}", e.getMessage());
		}
		return models;
	}

	static AntigravityResponse call(
			String model,
			String payload,
			String accessToken
	) throws IOException {
		String url = buildUrl();
		Map<String, String> headers = buildHeaders(accessToken);

		for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
			try {
				HttpRequest.Builder reqBuilder =
						HttpRequest.newBuilder()
						           .uri(URI.create(url));
				headers.forEach(reqBuilder::header);
				reqBuilder.POST(HttpRequest.BodyPublishers.ofString(payload));

				HttpResponse<InputStream> response = HTTP_CLIENT.send(
						reqBuilder.build(),
						HttpResponse.BodyHandlers.ofInputStream()
				);
				int status = response.statusCode();

				if (status == 200) {
					return new AntigravityResponse(200, response.body());
				}

				if (status == 429 || status >= 500) {
					long delay = BASE_RETRY_DELAY * (1L << attempt);
					log.warn(
							"[Antigravity] {} from upstream, waiting {}ms (attempt {}/{})",
							status,
							delay,
							attempt + 1,
							MAX_RETRIES
					);
					Thread.sleep(delay);
					continue;
				}

				return new AntigravityResponse(status, response.body());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Request interrupted", e);
			} catch (IOException e) {
				if (attempt == MAX_RETRIES - 1) throw e;
				log.warn(
						"[Antigravity] Network error: {}, retrying",
						e.getMessage()
				);
			}
		}
		throw new IOException("All retries exhausted");
	}
}
