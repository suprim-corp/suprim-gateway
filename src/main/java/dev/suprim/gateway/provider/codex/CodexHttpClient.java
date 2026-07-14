package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;

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
import java.util.Optional;

@Slf4j
public class CodexHttpClient {

	private static final long BASE_RETRY_DELAY = 1000;
	private static final int MAX_RETRIES = 3;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(15))
			          .build();

	@Builder
	record CodexResponse(int status, InputStream body) {}

	static List<Map<String, Object>> listModels(String accessToken) throws IOException {
		HttpRequest request =
				HttpRequest.newBuilder()
				           .uri(
						           URI.create(
								           Codex.API_BASE +
								           "/models?client_version=0.144.4"
						           )
				           )
				           .header(
						           "Authorization",
						           "Bearer " + accessToken
				           )
				           .header("originator", "codex_cli_rs")
				           .header(
						           "User-Agent",
						           "codex_cli_rs/0.136.0"
				           )
				           .GET()
				           .build();
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() != 200) {
				log.warn(
						"[Codex] listModels returned {}: {}",
						response.statusCode(),
						response.body()
				);
				return List.of();
			}
			JsonNode root = MAPPER.readTree(response.body());
			JsonNode data = root.get("models");
			if (data == null || !data.isArray()) {
				return List.of();
			}
			List<Map<String, Object>> models = new ArrayList<>();
			for (JsonNode item : data) {
				Optional.ofNullable(item.get("slug"))
				        .map(JsonNode::asString)
				        .filter(id -> !id.isEmpty())
				        .ifPresent(id -> models.add(
								        Map.of(
										        "id",
										        "codex/" + id
								        )
						        )
				        );
			}
			return models;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("listModels interrupted", e);
		}
	}

	static CodexResponse call(
			String payload,
			String accessToken
	) throws IOException {
		String url = Codex.API_BASE + "/responses";

		for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
			try {
				HttpRequest request =
						HttpRequest.newBuilder()
						           .uri(URI.create(url))
						           .header(
								           "Authorization",
								           "Bearer " + accessToken
						           )
						           .header("Content-Type", "application/json")
						           .header("originator", "codex_cli_rs")
						           .header("User-Agent", "codex_cli_rs/0.136.0")
						           .POST(
								           HttpRequest.BodyPublishers.ofString(
										           payload
								           )
						           )
						           .build();

				HttpResponse<InputStream> response = HTTP_CLIENT.send(
						request,
						HttpResponse.BodyHandlers.ofInputStream()
				);
				int status = response.statusCode();

				if (status == 200) {
					return CodexResponse.builder()
					                    .status(200)
					                    .body(response.body())
					                    .build();
				}

				if (status == 429 || status >= 500) {
					long delay = BASE_RETRY_DELAY * (1L << attempt);
					log.warn(
							"[Codex] {} from upstream, waiting {}ms (attempt {}/{})",
							status,
							delay,
							attempt + 1,
							MAX_RETRIES
					);
					Thread.sleep(delay);
					continue;
				}

				return CodexResponse.builder()
				                    .status(status)
				                    .body(response.body())
				                    .build();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Request interrupted", e);
			} catch (IOException e) {
				if (attempt == MAX_RETRIES - 1) throw e;
				log.warn("[Codex] Network error: {}, retrying", e.getMessage());
			}
		}
		throw new IOException("All retries exhausted");
	}

	public static Map<String, Object> fetchUsage(String accessToken) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(URI.create(
					                                 "https://chatgpt.com/backend-api/wham/usage"))
			                                 .header(
					                                 "Authorization",
					                                 "Bearer " + accessToken
			                                 )
			                                 .header(
					                                 "originator",
					                                 "codex_cli_rs"
			                                 )
			                                 .header(
					                                 "User-Agent",
					                                 "codex_cli_rs/0.136.0"
			                                 )
			                                 .GET()
			                                 .build();

			HttpResponse<String> response = HTTP_CLIENT.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() != 200) {
				log.warn("[Codex] usage returned {}", response.statusCode());
				return Map.of(
						"message",
						"Usage unavailable (" + response.statusCode() + ")"
				);
			}

			JsonNode root = MAPPER.readTree(response.body());
			Map<String, Object> result = new HashMap<>();

			Optional.ofNullable(root.get("plan_type"))
			        .map(JsonNode::asString)
			        .ifPresent(plan -> result.put("plan", plan));

			JsonNode rateLimit = root.get("rate_limit");
			if (rateLimit != null) {
				result.put(
						"limitReached",
						rateLimit.has("limit_reached") &&
						rateLimit.get("limit_reached").asBoolean()
				);

				JsonNode primaryNode = Optional.ofNullable(rateLimit.get(
						                               "primary_window"))
				                               .orElse(rateLimit.get("primary"));
				if (primaryNode != null) {
					Map<String, Object> session = new HashMap<>();
					session.put(
							"usedPercent",
							primaryNode.has("used_percent") ? primaryNode.get(
									"used_percent").asInt() : 0
					);
					Optional.ofNullable(primaryNode.get("reset_at"))
					        .or(() -> Optional.ofNullable(primaryNode.get(
							        "resets_at")))
					        .map(JsonNode::asString)
					        .ifPresent(r -> session.put("resetAt", r));
					result.put("session", session);
				}

				JsonNode secondaryNode =
						Optional.ofNullable(rateLimit.get("secondary_window"))
						        .orElse(rateLimit.get("secondary"));
				if (secondaryNode != null) {
					Map<String, Object> weekly = new HashMap<>();
					weekly.put(
							"usedPercent",
							secondaryNode.has("used_percent") ? secondaryNode.get(
									"used_percent").asInt() : 0
					);
					Optional.ofNullable(secondaryNode.get("reset_at"))
					        .or(() -> Optional.ofNullable(
									        secondaryNode.get("resets_at")
							        )
					        )
					        .map(JsonNode::asString)
					        .ifPresent(r -> weekly.put("resetAt", r));
					result.put("weekly", weekly);
				}
			}

			Optional.ofNullable(root.get("rate_limit_reset_credits"))
			        .map(c -> c.get("available_count"))
			        .map(JsonNode::asInt)
			        .ifPresent(count -> result.put("resetCredits", count));

			return result;
		} catch (Exception e) {
			log.error("[Codex] Failed to fetch usage: {}", e.getMessage());
			return Map.of("message", "Failed: " + e.getMessage());
		}
	}
}
