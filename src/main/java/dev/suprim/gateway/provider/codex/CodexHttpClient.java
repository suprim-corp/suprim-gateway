package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;
import dev.suprim.gateway.provider.UsageFailure;
import dev.suprim.gateway.proxy.ProxyChain;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient DIRECT_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(15))
			          .build();

	@Builder
	record CodexResponse(int status, InputStream body) {}

	private static HttpClient resolveClient(ProxyChain proxyChain) {
		HttpClient proxied = proxyChain.currentClient();
		return proxied != null ? proxied : DIRECT_CLIENT;
	}

	static List<Map<String, Object>> listModels(
			String accessToken,
			ProxyChain proxyChain
	) throws IOException {
		HttpRequest.Builder builder =
				HttpRequest.newBuilder()
				           .uri(
						           URI.create(
								           Codex.API_BASE + "/models" +
								           "?client_version=" +
								           Codex.CLIENT_VERSION
						           )
				           )
				           .GET();
		CodexHeaders.apply(builder, accessToken);
		HttpRequest request = builder.build();
		try {
			HttpResponse<String> response = resolveClient(proxyChain).send(
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
				        .ifPresent(slug -> {
					        Map<String, Object> model = new HashMap<>();
					        model.put("id", "codex/" + slug);
					        String displayName = Optional.ofNullable(item.get("name"))
					                .or(() -> Optional.ofNullable(item.get("display_name")))
					                .map(JsonNode::asString)
					                .orElse(Codex.MODEL_NAMES.get(slug));
					        if (displayName != null) {
						        model.put("displayName", displayName);
					        }
					        models.add(model);
				        });
			}
			return models;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("listModels interrupted", e);
		}
	}

	static CodexResponse call(
			String payload,
			String accessToken,
			ProxyChain proxyChain
	) throws IOException {
		String sessionId = null;
		try {
			JsonNode payloadNode = MAPPER.readTree(payload);
			JsonNode cacheKey = payloadNode.get("prompt_cache_key");
			if (cacheKey != null && cacheKey.isString()) {
				sessionId = cacheKey.asString();
			}
		} catch (Exception ignored) {
			// The payload is constructed locally; omit identity if it cannot be read.
		}
		return call(payload, accessToken, proxyChain, sessionId);
	}

	static CodexResponse call(
			String payload,
			String accessToken,
			ProxyChain proxyChain,
			String clientSessionId
	) throws IOException {
		String url = Codex.API_BASE + "/responses";

		for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
			try {
				HttpRequest.Builder builder =
						HttpRequest.newBuilder()
						           .uri(URI.create(url))
						           .header("Content-Type", "application/json")
						           .POST(
								           HttpRequest.BodyPublishers.ofString(
										           payload
								           )
						           );
				CodexHeaders.apply(builder, accessToken);
				if (clientSessionId != null && !clientSessionId.isBlank()) {
					builder.header("session_id", clientSessionId.trim());
				}
				HttpRequest request = builder.build();

				HttpResponse<InputStream> response = resolveClient(proxyChain).send(
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
					if (attempt == MAX_RETRIES - 1) {
						return CodexResponse.builder()
						                    .status(status)
						                    .body(response.body())
						                    .build();
					}
					long delay = BASE_RETRY_DELAY * (1L << attempt);
					log.warn(
							"[Codex] {} from upstream, waiting {}ms (attempt {}/{})",
							status,
							delay,
							attempt + 1,
							MAX_RETRIES
					);
					try (InputStream ignored = response.body()) {
						ignored.readAllBytes();
					}
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

	/**
	 * Reads the account's rate-limit windows from the ChatGPT backend.
	 * <p>
	 * Codex chooses the path from the configured base URL, not from the account
	 * or response status: a base containing {@code /backend-api} uses the
	 * ChatGPT path {@code /wham/usage}; a Codex API base uses
	 * {@code /api/codex/usage}. This gateway authenticates against
	 * {@code chatgpt.com/backend-api}, so only the former is valid.
	 */
	public static CodexUsage fetchUsage(
			String accessToken,
			ProxyChain proxyChain
	) {
		return fetchUsage(accessToken, proxyChain, Codex.CHATGPT_BASE);
	}

	static CodexUsage fetchUsage(
			String accessToken,
			ProxyChain proxyChain,
			String base
	) {
		try {
			HttpResponse<String> response = getUsage(
					base + "/wham/usage",
					accessToken,
					proxyChain
			);
			if (response.statusCode() != 200) {
				log.warn("[Codex] usage returned {}", response.statusCode());
				String message = "Usage unavailable (" + response.statusCode() + ")";
				// A rejected credential is a different fact from an upstream that is merely down:
				// the first means this account is unusable until re-authorised, the second fixes
				// itself. Only the former is flagged, so a 5xx never marks an account as bad.
				return UsageFailure.isUnauthorized(response.statusCode())
						? CodexUsage.rejected(message)
						: CodexUsage.failure(message);
			}

			return parseUsage(response.body());
		} catch (Exception e) {
			log.error("[Codex] Failed to fetch usage: {}", e.getMessage());
			return CodexUsage.failure("Failed: " + e.getMessage());
		}
	}

	static CodexUsage parseUsage(String body) {
		try {
			JsonNode root = MAPPER.readTree(body);
			JsonNode rateLimit = root.get("rate_limit");

			return CodexUsage.builder()
			                 .plan(text(root, "plan_type"))
			                 // Absent when the upstream reports no rate limit at all, rather than
			                 // asserting "not reached" about a limit it never described.
			                 .limitReached(
					                 rateLimit != null
							                 ? bool(rateLimit, "limit_reached")
							                 : null
			                 )
			                 .session(window(rateLimit, "primary_window", "primary"))
			                 .weekly(window(rateLimit, "secondary_window", "secondary"))
			                 .resetCredits(
					                 Optional.ofNullable(root.get("rate_limit_reset_credits"))
					                         .map(credits -> credits.get("available_count"))
					                         .map(JsonNode::asInt)
					                         .orElse(null)
			                 )
			                 .build();
		} catch (Exception e) {
			log.error("[Codex] Failed to read usage response: {}", e.getMessage());
			return CodexUsage.failure("Failed: " + e.getMessage());
		}
	}

	/**
	 * One rate-limit window, absent when the upstream reports neither naming for it. The two names
	 * are the same window: the upstream renamed these fields and still answers with either.
	 */
	private static CodexUsage.Window window(
			JsonNode rateLimit,
			String preferredName,
			String legacyName
	) {
		if (rateLimit == null) {
			return null;
		}
		JsonNode node = Optional.ofNullable(rateLimit.get(preferredName))
		                        .orElseGet(() -> rateLimit.get(legacyName));
		if (node == null) {
			return null;
		}
		// A window with no percentage reported reads as untouched rather than as missing: the
		// upstream omits the field at 0 rather than sending a zero.
		int usedPercent = Optional.ofNullable(node.get("used_percent"))
		                          .map(JsonNode::asInt)
		                          .orElse(0);
		String resetAt = Optional.ofNullable(node.get("reset_at"))
		                         .or(() -> Optional.ofNullable(node.get("resets_at")))
		                         .map(JsonNode::asString)
		                         .orElse(null);
		return CodexUsage.Window.builder()
		                        .usedPercent(usedPercent)
		                        .resetAt(resetAt)
		                        .build();
	}

	private static String text(JsonNode node, String field) {
		return Optional.ofNullable(node.get(field))
		               .map(JsonNode::asString)
		               .orElse(null);
	}

	private static boolean bool(JsonNode node, String field) {
		return Optional.ofNullable(node.get(field))
		               .map(JsonNode::asBoolean)
		               .orElse(false);
	}

	private static HttpResponse<String> getUsage(
			String url,
			String accessToken,
			ProxyChain proxyChain
	) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
		                                        .uri(URI.create(url))
		                                        .GET();
		CodexHeaders.apply(builder, accessToken);
		return resolveClient(proxyChain).send(
				builder.build(),
				HttpResponse.BodyHandlers.ofString()
		);
	}
}
