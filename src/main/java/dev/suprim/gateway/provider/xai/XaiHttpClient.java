package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.instants.Xai;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
class XaiHttpClient {

	private static final long BASE_RETRY_DELAY = 1000;
	private static final int MAX_RETRIES = 3;

	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(15))
			          .build();

	@Builder
	record XaiResponse(int status, InputStream body) {}

	static Map<String, String> buildHeaders(String accessToken) {
		return Map.of(
				"Authorization", "Bearer " + accessToken,
				"Content-Type", "application/json"
		);
	}

	static List<Map<String, Object>> listModels(String accessToken) throws IOException {
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(Xai.API_BASE + "/models"))
		                                 .header("Authorization", "Bearer " + accessToken)
		                                 .GET()
		                                 .build();
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("[xAI] listModels returned {}: {}", response.statusCode(), response.body());
				return List.of();
			}
			ObjectMapper mapper = new ObjectMapper();
			JsonNode root = mapper.readTree(response.body());
			JsonNode data = root.get("data");
			if (data == null || !data.isArray()) {
				return List.of();
			}
			List<Map<String, Object>> models = new ArrayList<>();
			for (JsonNode item : data) {
				String id = item.has("id") ? item.get("id").asString() : null;
				if (id != null && !id.isEmpty()) {
					Map<String, Object> model = new HashMap<>();
					model.put("id", "grok/" + id);
					String displayName = Xai.MODEL_NAMES.get(id);
					if (displayName != null) {
						model.put("displayName", displayName);
					}
					models.add(model);
				}
			}
			return models;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("listModels interrupted", e);
		}
	}

	static XaiResponse call(String payload, String accessToken) throws IOException {
		String url = Xai.API_BASE + "/chat/completions";
		Map<String, String> headers = buildHeaders(accessToken);

		for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
			try {
				HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(url));
				headers.forEach(reqBuilder::header);
				reqBuilder.POST(HttpRequest.BodyPublishers.ofString(payload));

				HttpResponse<InputStream> response = HTTP_CLIENT.send(
						reqBuilder.build(),
						HttpResponse.BodyHandlers.ofInputStream()
				);
				int status = response.statusCode();

				if (status == 200) {
					return new XaiResponse(200, response.body());
				}

				if (status == 429 || status >= 500) {
					long delay = BASE_RETRY_DELAY * (1L << attempt);
					log.warn("[xAI] {} from upstream, waiting {}ms (attempt {}/{})", status, delay, attempt + 1, MAX_RETRIES);
					Thread.sleep(delay);
					continue;
				}

				return new XaiResponse(status, response.body());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Request interrupted", e);
			} catch (IOException e) {
				if (attempt == MAX_RETRIES - 1) throw e;
				log.warn("[xAI] Network error: {}, retrying", e.getMessage());
			}
		}
		throw new IOException("All retries exhausted");
	}
}
