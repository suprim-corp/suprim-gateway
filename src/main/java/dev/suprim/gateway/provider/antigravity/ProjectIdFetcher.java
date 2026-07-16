package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
class ProjectIdFetcher {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(10))
			          .build();

	private static final String[] IDE_TYPES = {"VSCODE", "JETBRAINS", "CLOUD_SHELL", "IDE_UNSPECIFIED"};

	static String fetch(String accessToken) throws IOException {
		for (String ideType : IDE_TYPES) {
			try {
				String body = "{\"metadata\":{\"ideType\":\"" + ideType + "\",\"platform\":\"PLATFORM_UNSPECIFIED\",\"pluginType\":\"GEMINI\"}}";
				HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
				                                            .uri(URI.create(Antigravity.CLOUDCODE_BASE + "/v1internal:loadCodeAssist"));
				buildHeaders(accessToken).forEach(reqBuilder::header);
				reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body));

				HttpResponse<String> response = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
				if (!isSuccess(response.statusCode())) {
					log.warn("[ProjectId] loadCodeAssist ({}) returned {}: {}", ideType, response.statusCode(), response.body());
					continue;
				}
				String responseBody = response.body();
				log.debug("[ProjectId] loadCodeAssist ({}) response: {}", ideType, responseBody);
				String projectId = parseProjectId(responseBody);
				if (projectId != null) {
					log.info("[ProjectId] Discovered projectId using {}: {}", ideType, projectId);
					return projectId;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("loadCodeAssist interrupted", e);
			} catch (Exception e) {
				log.warn("[ProjectId] Failed loadCodeAssist with {}: {}", ideType, e.getMessage());
			}
		}
		return null;
	}

	static String buildLoadCodeAssistBody() {
		return "{\"metadata\":{\"ideType\":\"VSCODE\",\"platform\":\"PLATFORM_UNSPECIFIED\",\"pluginType\":\"GEMINI\"}}";
	}

	static String parseTier(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			JsonNode allowedTiers = node.get("allowedTiers");
			if (allowedTiers != null && allowedTiers.isArray() && !allowedTiers.isEmpty()) {
				JsonNode first = allowedTiers.get(0);
				JsonNode name = first.get("name");
				JsonNode desc = first.get("description");
				if (name != null && !name.isNull()) {
					String tierName = name.asString();
					if (desc != null && !desc.isNull()) {
						return tierName + " — " + desc.asString();
					}
					return tierName;
				}
			}
		} catch (Exception ignored) {}
		return null;
	}

	static String parseProjectId(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			JsonNode project = node.get("cloudaicompanionProject");
			if (project == null || project.isNull()) return null;
			if (project.isObject()) {
				JsonNode id = project.get("id");
				return id != null && !id.isNull() ? id.asString() : null;
			}
			String val = project.asString();
			return val != null && !val.isEmpty() ? val : null;
		} catch (Exception e) {
			return null;
		}
	}

	static String parseOnboardResponse(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			if (!node.has("done") || !node.get("done").asBoolean()) return null;
			JsonNode response = node.get("response");
			if (response == null) return null;
			JsonNode project = response.get("cloudaicompanionProject");
			if (project == null || project.isNull()) return null;
			if (project.isObject()) {
				JsonNode id = project.get("id");
				return id != null && !id.isNull() ? id.asString() : null;
			}
			String val = project.asString();
			return val != null && !val.isEmpty() ? val : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, String> buildHeaders(String accessToken) {
		return Map.of(
				"Authorization", "Bearer " + accessToken,
				"Content-Type", "application/json",
				"User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/2.0.1 Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36",
				"X-Goog-Api-Client", "google-cloud-sdk vscode/1.96.0",
				"Client-Metadata", "{\"ideType\":\"VSCODE\",\"platform\":\"MACOS\",\"pluginType\":\"GEMINI\",\"osVersion\":\"15.1\",\"arch\":\"arm64\"}"
		);
	}

	private static boolean isSuccess(int status) {
		return status >= 200 && status < 300;
	}
}
