package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP calls against the {@code cloudcode-pa.googleapis.com} {@code v1internal} RPCs.
 * <p>
 * The streaming RPC goes out directly and returns an unread body, since the caller relays
 * the SSE stream as it arrives. The control-plane RPCs go through a {@link ProxyChain},
 * read their response in full, and return parsed values.
 */
@Slf4j
class AntigravityHttpClient {

	private static final long BASE_RETRY_DELAY = 1000;
	private static final int MAX_RETRIES = 3;

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
	                                                        .connectTimeout(
			                                                        Duration.ofSeconds(
					                                                        15))
	                                                        .build();

	/**
	 * An upstream response whose {@code body} may still be streaming. Ownership passes to
	 * the receiver, which must close it.
	 */
	@Builder
	record AntigravityResponse(int status, InputStream body) {}

	/**
	 * Streaming endpoint. {@code alt=sse} is what makes the upstream answer with SSE.
	 */
	static String buildUrl() {
		return Antigravity.CLOUDCODE_BASE +
		       "/v1internal:streamGenerateContent?alt=sse";
	}

	/**
	 * Headers for the streaming RPC. Deliberately leaner than
	 * {@link AntigravityHeaders#forControlPlane} and with a different {@code User-Agent} —
	 * the streaming endpoint expects the IDE's own agent string.
	 */
	static Map<String, String> buildHeaders(String accessToken) {
		return Map.of(
				"Authorization",
				"Bearer " + accessToken,
				"Content-Type",
				"application/json",
				"User-Agent",
				Antigravity.USER_AGENT
		);
	}

	/**
	 * Calls {@code fetchAvailableModels}, returning one map per model with {@code id} and,
	 * when the upstream reports them, {@code quota} (percent remaining), {@code displayName},
	 * and the capability keys listed on {@link #copyCapabilities}. Returns an empty list on
	 * any non-200 or unparseable response.
	 */
	static List<Map<String, Object>> listModels(
			String accessToken,
			String projectId,
			ProxyChain proxyChain
	) throws IOException {
		String body = buildProjectBody(projectId);
		HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
		                                            .uri(URI.create(
				                                            Antigravity.CLOUDCODE_BASE +
				                                            "/v1internal:fetchAvailableModels"));
		Map<String, String> headers = AntigravityHeaders.forControlPlane(
				accessToken);
		headers.forEach(reqBuilder::header);
		reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
		try {
			HttpResponse<String> response = proxyChain.send(reqBuilder.build());
			if (response.statusCode() != 200) {
				log.warn(
						"[Antigravity] listModels returned {}: {}",
						response.statusCode(),
						response.body()
				);
				return List.of();
			}
			return parseModelsWithQuota(response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("listModels interrupted", e);
		}
	}

	/**
	 * Calls {@code retrieveUserQuotaSummary} and returns the normalized shape described on
	 * {@link #parseQuotaSummary}. Returns an empty map when the upstream does not answer 200
	 * or reports no usable quota.
	 */
	static AntigravityQuota getQuotaSummary(
			String accessToken,
			String projectId,
			ProxyChain proxyChain
	) throws IOException {
		HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
		                                            .uri(URI.create(
				                                            Antigravity.CLOUDCODE_BASE +
				                                            "/v1internal:retrieveUserQuotaSummary"));
		AntigravityHeaders.forControlPlane(accessToken)
		                  .forEach(reqBuilder::header);
		reqBuilder.POST(HttpRequest.BodyPublishers.ofString(buildProjectBody(
				projectId)));
		try {
			HttpResponse<String> response = proxyChain.send(reqBuilder.build());
			if (response.statusCode() != 200) {
				log.debug(
						"[Antigravity] retrieveUserQuotaSummary returned {}",
						response.statusCode()
				);
				// A refused credential is reported so the accounts page can stop calling the
				// account connected. Other failures stay indistinguishable from "no quota",
				// since they resolve on their own.
				return UsageFailure.isUnauthorized(response.statusCode())
						? AntigravityQuota.rejected()
						: AntigravityQuota.none();
			}
			return parseQuotaSummary(response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("retrieveUserQuotaSummary interrupted", e);
		}
	}

	/**
	 * Body shared by the control-plane RPCs, which take only the project. An unknown project
	 * sends {@code {}} — the backend then resolves it from the token.
	 */
	static String buildProjectBody(String projectId) {
		return projectId != null && !projectId.isEmpty() ?
				"{\"project\":\"" + projectId + "\"}" : "{}";
	}

	/**
	 * Normalizes a quota response into {@code buckets} — one entry per quota window, each
	 * with {@code group}, {@code label}, and, when reported, {@code resetTime} and
	 * {@code description}. What is left is reported as {@code quota} (percent remaining)
	 * for windows that give a fraction, and as {@code remaining} (an absolute count) for
	 * windows that give a unit count instead; a bucket carries one or the other, never
	 * both.
	 * <p>
	 * Top-level {@code quota}/{@code resetTime} mirror the <em>most constrained</em>
	 * percent-based bucket, since that is the one that will actually stop a request.
	 * Count-based buckets are deliberately left out of that comparison: the upstream does
	 * not report the window's total, so a count cannot be ranked against a percent.
	 * <p>
	 * A response with no groups falls back to searching by field name, which covers the
	 * flatter shape some endpoints return. Fractions outside 0..1 are treated as unusable,
	 * and an unparseable response yields an empty map rather than an error.
	 */
	static AntigravityQuota parseQuotaSummary(String json) {
		try {
			UserQuota.Summary summary = new JsonMapper().readValue(
					json,
					UserQuota.Summary.class
			);
			List<AntigravityQuota.Bucket> buckets = collectBuckets(summary);
			if (buckets.isEmpty()) {
				return parseFlatQuota(json);
			}

			// Count-based buckets are deliberately left out of this comparison: without the
			// window's total, a count cannot be ranked against a percent.
			Optional<AntigravityQuota.Bucket> tightest =
					buckets.stream()
					       .filter(bucket ->
							       bucket.quota() != null
					       )
					       .min(
							       Comparator.comparingInt(
									       AntigravityQuota.Bucket::quota
							       )
					       );

			return AntigravityQuota.builder()
			                       .quota(
					                       tightest.map(
							                               AntigravityQuota.Bucket::quota
					                               )
					                               .orElse(null)
			                       )
			                       .resetTime(
					                       tightest.map(AntigravityQuota.Bucket::resetTime)
					                               .orElse(null)
			                       )
			                       .buckets(buckets)
			                       .build();
		} catch (Exception ignored) {
			return parseFlatQuota(json);
		}
	}

	private static List<AntigravityQuota.Bucket> collectBuckets(UserQuota.Summary summary) {
		List<AntigravityQuota.Bucket> buckets = new ArrayList<>();
		if (summary == null || summary.groups() == null) {
			return buckets;
		}
		for (UserQuota.Summary.Group group : summary.groups()) {
			if (group.buckets() == null) {
				continue;
			}
			for (UserQuota.Summary.Bucket bucket : group.buckets()) {
				if (!bucket.hasUsableFraction() && !bucket.hasUsableAmount()) {
					continue;
				}
				// A bucket reports a fraction or a count, never both — whichever arm it uses, the
				// other stays absent.
				buckets.add(
						AntigravityQuota.Bucket.builder()
						                       .group(group.displayName())
						                       .label(bucket.displayName())
						                       .quota(bucket.hasUsableFraction() ? bucket.remainingPercent() : null)
						                       .remaining(bucket.hasUsableFraction() ? null : bucket.remainingAmount())
						                       .resetTime(bucket.resetTime())
						                       .description(bucket.description())
						                       .build()
				);
			}
		}
		return buckets;
	}

	/**
	 * Fallback for responses that carry a single fraction rather than grouped buckets.
	 */
	private static AntigravityQuota parseFlatQuota(String json) {
		try {
			JsonNode root = new JsonMapper().readTree(json);
			JsonNode fraction = root.findValue("remainingFraction");
			if (fraction == null || !fraction.isNumber()) {
				return AntigravityQuota.none();
			}
			double value = fraction.asDouble();
			if (value < 0 || value > 1) {
				return AntigravityQuota.none();
			}
			JsonNode resetTime = root.findValue("resetTime");
			return AntigravityQuota.builder()
			                       .quota((int) Math.round(value * 100))
			                       .resetTime(
					                       resetTime != null &&
					                       resetTime.isString() ? resetTime.asString() : null
			                       )
			                       .build();
		} catch (Exception ignored) {
			return AntigravityQuota.none();
		}
	}

	/**
	 * Copies the capability fields the upstream reports for one model onto {@code item}.
	 * <p>
	 * Absent fields are left out rather than defaulted, so a caller can tell "the upstream
	 * says no" apart from "the upstream did not say".
	 * <p>
	 * Image support comes from the {@code supportsImages} boolean, but PDF, audio and video
	 * are read from {@code supportedMimeTypes}, which is exhaustive when present and is the
	 * only signal for PDF and audio. The {@code supportsVideo} boolean is not authoritative:
	 * models carrying seven {@code video/*} MIME types omit it entirely, so trusting it alone
	 * would under-report video. It is used only as a fallback when no MIME list is given.
	 */
	private static void copyCapabilities(
			JsonNode model,
			Map<String, Object> item
	) {
		if (model.has("supportsImages")) {
			item.put("supportsImages", model.get("supportsImages").asBoolean());
		}
		if (model.has("supportsThinking")) {
			item.put(
					"supportsThinking",
					model.get("supportsThinking").asBoolean()
			);
		}
		if (model.has("thinkingBudget")) {
			item.put("thinkingBudget", model.get("thinkingBudget").asInt());
		}
		if (model.has("minThinkingBudget")) {
			item.put(
					"minThinkingBudget",
					model.get("minThinkingBudget").asInt()
			);
		}
		if (model.has("maxTokens")) {
			item.put("maxInputTokens", model.get("maxTokens").asInt());
		}
		if (model.has("maxOutputTokens")) {
			item.put("maxOutputTokens", model.get("maxOutputTokens").asInt());
		}
		JsonNode mimeTypes = model.get("supportedMimeTypes");
		if (mimeTypes != null && mimeTypes.isObject()) {
			item.put("supportsPdf", mimeTypes.has("application/pdf"));
			item.put("supportsAudio", hasMimeFamily(mimeTypes, "audio/"));
			item.put("supportsVideo", hasMimeFamily(mimeTypes, "video/"));
		} else if (model.has("supportsVideo")) {
			item.put("supportsVideo", model.get("supportsVideo").asBoolean());
		}
	}

	/**
	 * Whether the MIME list names any type in one family. Video is a family rather than a
	 * single type, and the upstream spells its own video entries inconsistently
	 * ({@code video/mp4} next to {@code video/audio/wav}), so matching the prefix is the only
	 * reliable read.
	 */
	private static boolean hasMimeFamily(JsonNode mimeTypes, String prefix) {
		return mimeTypes.propertyNames()
		                .stream()
		                .anyMatch(mime -> mime.startsWith(prefix));
	}

	/**
	 * Flattens a models response into one map per model. Handles both shapes the upstream
	 * returns: {@code availableModels} keyed by model name, and {@code models} as an array of
	 * wrappers. Either way the {@code models/} prefix is stripped and quota, when reported,
	 * becomes a whole percent. Malformed input yields an empty list rather than an error.
	 */
	static List<Map<String, Object>> parseModelsWithQuota(String json) {
		List<Map<String, Object>> models = new ArrayList<>();
		try {
			JsonNode root = new JsonMapper().readTree(json);
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
					if (modelId.isEmpty() || modelId.contains(" ")) {
						continue;
					}

					JsonNode value = entry.getValue();
					int quotaPct = -1;
					JsonNode quotaInfo = value.get("quotaInfo");
					if (quotaInfo != null &&
					    quotaInfo.has("remainingFraction")) {
						double fraction = quotaInfo.get("remainingFraction")
						                           .asDouble();
						quotaPct = (int) Math.round(fraction * 100);
					}
					String displayName = value.has("displayName") ? value.get(
							"displayName").asString() : null;
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("id", modelId);
					if (quotaPct >= 0) item.put("quota", quotaPct);
					if (displayName != null) {
						item.put("displayName", displayName);
					}
					copyCapabilities(value, item);
					models.add(item);
				}
			} else if (available.isArray()) {
				for (JsonNode arrayItem : available) {
					JsonNode model = arrayItem.get("model");
					if (model != null && model.has("name")) {
						String name = model.get("name").asString();
						String modelId = name.startsWith("models/") ? name.substring(
								7) : name;

						int quotaPct = -1;
						JsonNode quotaInfo = arrayItem.get("quotaInfo");
						if (quotaInfo != null && quotaInfo.has(
								"remainingFraction")) {
							double fraction = quotaInfo.get("remainingFraction")
							                           .asDouble();
							quotaPct = (int) Math.round(fraction * 100);
						}
						String displayName = model.has("displayName") ? model.get(
								"displayName").asString() : null;
						Map<String, Object> item = new LinkedHashMap<>();
						item.put("id", modelId);
						if (quotaPct >= 0) item.put("quota", quotaPct);
						if (displayName != null) item.put(
								"displayName",
								displayName
						);
						copyCapabilities(model, item);
						models.add(item);
					}
				}
			}
		} catch (Exception e) {
			log.warn(
					"[Antigravity] Failed to parse models response: {}",
					e.getMessage()
			);
		}
		return models;
	}

	/**
	 * Calls {@code streamGenerateContent} and hands back the still-open SSE body for the
	 * caller to read. The caller owns the stream and must close it.
	 *
	 * @param model   the requested model; carried for logging and test stubbing only, as the
	 *                model is named inside {@code payload} rather than in the URL
	 * @param payload the serialized request body
	 */
	static AntigravityResponse streamGenerateContent(
			String model,
			String payload,
			String accessToken
	) throws IOException {
		return postWithRetry(
				buildUrl(),
				payload,
				accessToken,
				BASE_RETRY_DELAY
		);
	}

	/**
	 * POSTs {@code payload} to {@code url}, retrying 429 and 5xx responses and network
	 * failures with exponential backoff, up to {@value #MAX_RETRIES} attempts in total.
	 * <p>
	 * The loop covers only the attempts that may still be retried; the final attempt runs
	 * after it, so whatever it produces — a response of any status, or an
	 * {@link IOException} — reaches the caller unchanged. A retried response body is
	 * drained first so the connection is not leaked.
	 * <p>
	 * The returned body is an open stream on success; retried responses are consumed
	 * internally and never returned.
	 *
	 * @param baseRetryDelay backoff base in milliseconds, doubling per attempt. Pass 0 to
	 *                       retry without waiting.
	 */
	static AntigravityResponse postWithRetry(
			String url,
			String payload,
			String accessToken,
			long baseRetryDelay
	) throws IOException {
		Map<String, String> headers = buildHeaders(accessToken);

		for (int attempt = 1; attempt < MAX_RETRIES; attempt++) {
			long delay = baseRetryDelay * (1L << (attempt - 1));
			try {
				AntigravityResponse response = send(url, payload, headers);
				if (!isRetryable(response.status())) {
					return response;
				}
				log.warn(
						"[Antigravity] {} from upstream, waiting {}ms (attempt {}/{})",
						response.status(),
						delay,
						attempt,
						MAX_RETRIES
				);
				drain(response);
			} catch (IOException e) {
				log.warn(
						"[Antigravity] Network error: {}, retrying in {}ms (attempt {}/{})",
						e.getMessage(),
						delay,
						attempt,
						MAX_RETRIES
				);
			}
			sleep(delay);
		}
		return send(url, payload, headers);
	}

	/**
	 * One attempt, no retry logic. Converts an interrupt into an {@link IOException} after
	 * restoring the thread's interrupt flag, so callers only have one failure type to handle.
	 */
	private static AntigravityResponse send(
			String url,
			String payload,
			Map<String, String> headers
	) throws IOException {
		HttpRequest.Builder reqBuilder =
				HttpRequest.newBuilder().uri(URI.create(url));
		headers.forEach(reqBuilder::header);
		reqBuilder.POST(HttpRequest.BodyPublishers.ofString(payload));
		try {
			HttpResponse<InputStream> response = HTTP_CLIENT.send(
					reqBuilder.build(),
					HttpResponse.BodyHandlers.ofInputStream()
			);
			return new AntigravityResponse(
					response.statusCode(),
					response.body()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Request interrupted", e);
		}
	}

	/**
	 * Whether the status is worth another attempt: rate limiting and server-side faults are,
	 * anything else (including 401/403) is a decision the upstream will repeat.
	 */
	private static boolean isRetryable(int status) {
		return status == 429 || status >= 500;
	}

	/**
	 * Reads and discards a body we are about to abandon, so the connection returns to the
	 * pool instead of leaking. A failure here is irrelevant — the response is already being
	 * thrown away.
	 */
	private static void drain(AntigravityResponse response) {
		try (InputStream body = response.body()) {
			body.readAllBytes();
		} catch (IOException ignored) {}
	}

	/**
	 * Backoff pause, surfacing an interrupt as {@link IOException} like {@link #send}.
	 */
	private static void sleep(long delay) throws IOException {
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Retry interrupted", e);
		}
	}
}
