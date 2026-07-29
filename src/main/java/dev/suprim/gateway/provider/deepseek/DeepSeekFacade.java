package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.logging.ProviderOutcome;
import dev.suprim.gateway.logging.RequestLogCall;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.SseHeartbeat;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.StreamingEventWriter;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Tool;
import dev.suprim.gateway.utils.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Main facade orchestrating DeepSeek Web API calls: pool → auth → session → PoW → completion → auto-continue → stream.
 */
@Slf4j
public class DeepSeekFacade {

	private static final int MAX_EMPTY_RETRIES = 3;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final DeepSeekHttpClient httpClient;
	private final DeepSeekAuthManager authManager;
	private final DeepSeekAccountPool accountPool;
	private final DeepSeekAutoContinue autoContinue;
	private final StreamConverter converter;
	private final SseHeartbeat sseHeartbeat;
	private final String baseUrl;

	public DeepSeekFacade(
			DeepSeekHttpClient httpClient,
			DeepSeekAuthManager authManager,
			DeepSeekAccountPool accountPool,
			DeepSeekAutoContinue autoContinue,
			StreamConverter converter,
			SseHeartbeat sseHeartbeat,
			String baseUrl
	) {
		this.httpClient = httpClient;
		this.authManager = authManager;
		this.accountPool = accountPool;
		this.autoContinue = autoContinue;
		this.converter = converter;
		this.sseHeartbeat = sseHeartbeat;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(
				0,
				baseUrl.length() - 1
		) : baseUrl;
	}

	ProviderOutcome handle(
			InternalRequest request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			Format format,
			HttpServletResponse httpRes
	) throws Exception {
		return handle(
				request,
				RequestLogCall.start(model, stream, inputTokens, keyId, clientIp, format),
				httpRes
		);
	}

	public ProviderOutcome handle(
			InternalRequest request,
			RequestLogCall call,
			HttpServletResponse httpRes
	) throws Exception {
		Set<String> triedAccounts = new HashSet<>();

		while (true) {
			StoredAccount account = accountPool.acquire(triedAccounts);
			if (account == null) {
				ErrorResponse.rateLimitOpenAi(httpRes);
				return ProviderOutcome.none();
			}

			try {
				String token = authManager.getToken(account);
				String chatSessionId = createChatSession(token);
				Integer outputTokens = attemptCompletion(
						request,
						chatSessionId,
						token,
						account,
						call.streaming(),
						call.format(),
						call.model(),
						httpRes
				);
				if (outputTokens != null) {
					return call.success(account.name(), null, outputTokens, null, null);
				}
				triedAccounts.add(account.name());
			} catch (IOException e) {
				log.warn(
						LogTag.DEEPSEEK + "Account {} failed: {}",
						account.name(),
						e.getMessage()
				);
				triedAccounts.add(account.name());
				authManager.invalidateToken(account);
			} finally {
				accountPool.release(account);
			}
		}
	}

	private Integer attemptCompletion(
			InternalRequest request,
			String chatSessionId,
			String token,
			StoredAccount account,
			boolean stream,
			Format format,
			String model,
			HttpServletResponse httpRes
	) throws Exception {
		for (int retry = 0; retry < MAX_EMPTY_RETRIES; retry++) {
			String powHeader = fetchAndSolvePow(token);
			String payload = DeepSeekRequestConverter.convert(
					request,
					chatSessionId
			);

			Request httpRequest = httpClient.buildPostRequest(
					baseUrl + "/api/v0/chat/completion",
					payload,
					token,
					powHeader
			);

			InputStream responseStream = httpClient.executeStream(httpRequest);

			httpRes.setStatus(200);
			try (SseHeartbeat.Session session = sseHeartbeat.open(httpRes)) {
				StreamingEventWriter eventWriter = new StreamingEventWriter(
						session.writer(), converter, format, model,
						format != Format.ANTHROPIC || request.thinkingEnabled()
				);

				Set<String> toolNames = extractToolNames(request);
				DeepSeekToolSieve toolSieve = new DeepSeekToolSieve(
						eventWriter.asConsumer(), toolNames
				);

				DeepSeekAutoContinue.Result result = autoContinue.process(
						responseStream, chatSessionId, token, powHeader,
						toolSieve::accept
				);
				toolSieve.flush();

				if (!eventWriter.hasOutput()) {
					if (retry < MAX_EMPTY_RETRIES - 1) {
						log.info(
								LogTag.DEEPSEEK +
								"Empty output on attempt {} (status={}), retrying",
								retry + 1, result.status()
						);
					}
				} else {
					int outputTokens = countOutputTokens(result);
					eventWriter.finish(outputTokens);
					return outputTokens;
				}
			}
		}
		return null;
	}

	private static int countOutputTokens(DeepSeekAutoContinue.Result result) {
		String content = result.events().stream()
		                       .filter(event -> "content".equals(event.type()) ||
		                                        "reasoning".equals(event.type()))
		                       .map(event -> event.content() == null ? "" : event.content())
		                       .collect(Collectors.joining());
		return Math.max(0, content.length() / 4);
	}

	private String createChatSession(String token) throws IOException {
		ObjectNode payload = JSON.createObjectNode();
		payload.put("agent", "chat");

		Request request = httpClient.buildPostRequest(
				baseUrl + "/api/v0/chat_session/create",
				payload.toString(),
				token,
				null
		);

		try (Response response = httpClient.execute(request)) {
			if (!response.isSuccessful() || response.body() == null) {
				throw new IOException(
						"Failed to create chat session: HTTP " +
				                      response.code()
				);
			}
			String responseBody = response.body().string();

			JsonNode root = JSON.readTree(responseBody);
			JsonNode bizData = root.path("data").path("biz_data");
			String sessionId = bizData.path("id").asString("");
			if (sessionId.isEmpty()) {
				sessionId = bizData.path("chat_session")
				                   .path("id")
				                   .asString("");
			}
			if (sessionId.isEmpty()) {
				throw new IOException("Chat session response missing id");
			}
			return sessionId;
		}
	}

	private String fetchAndSolvePow(String token) throws IOException {
		ObjectNode body = JSON.createObjectNode();
		body.put("target_path", "/api/v0/chat/completion");

		Request request = httpClient.buildPostRequest(
				baseUrl + "/api/v0/chat/create_pow_challenge",
				body.toString(),
				token,
				null
		);

		try (Response response = httpClient.execute(request)) {
			if (!response.isSuccessful() || response.body() == null) {
				throw new IOException(
						"Failed to fetch PoW challenge: HTTP " +
						response.code()
				);
			}
			String responseBody = response.body().string();
			JsonNode root = JSON.readTree(responseBody);
			JsonNode biz = root.path("data").path("biz_data");

			JsonNode challengeNode = biz.path("challenge");
			if (challengeNode.isMissingNode() || !challengeNode.isObject()) {
				throw new IOException(
						"PoW response missing challenge data: " + biz
				);
			}

			String algorithm = challengeNode.path("algorithm").stringValue();
			String challenge = challengeNode.path("challenge").stringValue();
			String salt = challengeNode.path("salt").stringValue();
			long difficulty = challengeNode.path("difficulty").asLong();
			long expireAt = challengeNode.path("expire_at").asLong();
			String signature = challengeNode.path("signature").stringValue();
			String targetPath = challengeNode.path("target_path").stringValue();

			if (algorithm == null || challenge == null || salt == null) {
				String alg2 = biz.path("algorithm").stringValue();
				String ch2 = biz.path("challenge").stringValue();
				String s2 = biz.path("salt").stringValue();
				if (alg2 != null && ch2 != null && s2 != null) {
					algorithm = alg2;
					challenge = ch2;
					salt = s2;
					difficulty = biz.path("difficulty").asLong();
					expireAt = biz.path("expire_at").asLong();
					signature = biz.path("signature").stringValue();
					targetPath = biz.path("target_path").stringValue();
				} else {
					throw new IOException("PoW challenge fields missing");
				}
			}

			long nonce = DeepSeekPowSolver.solve(
					challenge,
					salt,
					expireAt,
					difficulty
			);
			if (nonce < 0) {
				throw new IOException(
						"PoW challenge unsolvable within difficulty " +
						difficulty
				);
			}

			return DeepSeekPowSolver.buildPowHeader(
					algorithm, challenge, salt, nonce, signature, targetPath
			);
		}
	}

	private Set<String> extractToolNames(InternalRequest request) {
		if (request.tools() == null || request.tools().isEmpty()) {
			return Set.of();
		}
		return request.tools().stream()
				.map(Tool::function)
				.filter(Objects::nonNull)
				.map(Tool.Function::name)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
	}

}
