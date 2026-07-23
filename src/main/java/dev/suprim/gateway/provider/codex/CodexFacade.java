package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.ProxyChain;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.StreamingEventWriter;
import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.utils.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
@Component
public class CodexFacade {

	private static final JsonMapper MAPPER = new JsonMapper();
	private final CodexAuthManager authManager;
	private final RequestLogPublisher logPublisher;
	private final AccountRotator accountRotator;
	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;
	private final CodexAccountCooldown accountCooldown;

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
				Provider.CODEX.name()
		);
		if (accounts.isEmpty()) {
			ErrorResponse.openAi(
					httpRes,
					401,
					"Codex provider not connected. Visit /auth/codex to connect.",
					"provider_not_connected"
			);
			return;
		}

		long startTime = System.currentTimeMillis();
		int maxAttempts = accounts.size();

		ObjectNode payloadNode = CodexRequestConverter.toPayload(
				model,
				request.messages(),
				request.tools(),
				true
		);
		if (request.temperature() != null) {
			payloadNode.put("temperature", request.temperature());
		}
		if (request.maxTokens() != null) {
			payloadNode.put("max_tokens", request.maxTokens());
		}
		mapSamplingToReasoning(payloadNode);
		String payload = MAPPER.writeValueAsString(payloadNode);

		Set<String> attemptedAccounts = new HashSet<>();
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			StoredAccount account = accountRotator.next(Provider.CODEX.name());
			String accountKey = accountCooldown.accountKey(account);
			if (!attemptedAccounts.add(accountKey) || accountCooldown.isCoolingDown(account)) {
				continue;
			}
			String accessToken;
			try {
				accessToken = authManager.getAccessToken(account);
			} catch (Exception e) {
				log.error(
						LogTag.CODEX + "Auth failed for {}: {}",
						account.name(),
						e.getMessage()
				);
				continue;
			}

			log.info(
					LogTag.CODEX + "Using account: {} (attempt {}/{})",
					account.name(), attempt + 1, maxAttempts
			);

			CodexHttpClient.CodexResponse response;
			try {
				response = CodexHttpClient.call(
						payload,
						accessToken,
						proxyChain
				);
			} catch (IOException e) {
				log.warn(
						LogTag.CODEX + "Upstream request failed: {}",
						e.getMessage()
				);
				ErrorResponse.openAi(
						httpRes,
						502,
						"Codex upstream unavailable",
						"upstream_unavailable"
				);
				return;
			}
			log.info(
					LogTag.CODEX + "Upstream responded with status {}",
					response.status()
			);

			if (response.status() == 429 || response.status() == 503) {
				accountCooldown.coolDown(account);
				log.warn(
						LogTag.CODEX + "Account {} got {}, cooling down for 6h",
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

			// Codex upstream returns Responses API SSE
			relayUpstream(
					response, account.name(), model, inputTokens,
					keyId, clientIp, startTime, format,
					request.thinkingEnabled(), httpRes
			);
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
			CodexHttpClient.CodexResponse response,
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
				LogTag.CODEX + "Upstream {} body: {}", response.status(),
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
				               .errorMessage(
						               body.length() > 200 ? body.substring(
								               0,
								               200
						               ) : body)
				               .build()
		);

		ErrorResponse.openAi(
				httpRes,
				response.status(),
				"Codex upstream error",
				"upstream_error"
		);
	}

	private void relayUpstream(
			CodexHttpClient.CodexResponse response,
			String accountName,
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			long startTime,
			Format format,
			boolean requestThinkingEnabled,
			HttpServletResponse httpRes
	) throws Exception {
		if (format == Format.RESPONSES) {
			// Client wants Responses API format — pass-through as-is
			passThrough(
					response,
					accountName,
					model,
					inputTokens,
					keyId,
					clientIp,
					startTime,
					httpRes
			);
			return;
		}

		// COMPLETION or ANTHROPIC — parse Responses API SSE and convert
		try (
				BufferedReader reader =
						new BufferedReader(new InputStreamReader(response.body()))
		) {
			String firstData = readFirstData(reader);
			if (firstData == null) {
				handleEmptyStream(
						accountName,
						model,
						inputTokens,
						keyId,
						clientIp,
						startTime,
						httpRes
				);
				return;
			}

			httpRes.setCharacterEncoding("UTF-8");
			httpRes.setContentType("text/event-stream; charset=utf-8");
			httpRes.setHeader("Cache-Control", "no-cache");
			PrintWriter writer = httpRes.getWriter();

			boolean thinkingEnabled =
					format != Format.ANTHROPIC || requestThinkingEnabled;
			StreamConverter converter = new StreamConverter();
			StreamingEventWriter eventWriter = new StreamingEventWriter(
					writer,
					converter,
					format,
					model,
					thinkingEnabled,
					inputTokens
			);

			Long firstTokenMs = null;
			int outputTokens = 0;
			String data = firstData;
			do {
				JsonNode node = MAPPER.readTree(data);
				Optional<KiroEvent> event = CodexSseMapper.toEvent(node);
				if (event.isPresent()) {
					if (firstTokenMs == null) {
						firstTokenMs = System.currentTimeMillis() - startTime;
					}
					eventWriter.write(event.get());
				}
				Optional<Integer> outTok = CodexSseMapper.usageOutputTokens(node);
				if (outTok.isPresent()) {
					outputTokens = outTok.get();
				}
				Optional<Integer> inTok = CodexSseMapper.usageInputTokens(node);
				if (inTok.isPresent()) {
					inputTokens = inTok.get();
				}
			} while ((data = readFirstData(reader)) != null);

			eventWriter.finish(outputTokens);

			int latency = (int) (System.currentTimeMillis() - startTime);
			logPublisher.publish(
					RequestLogEvent.builder()
					               .virtualKeyId(keyId)
					               .accountId(accountName)
					               .model(model)
					               .requestedModel(model)
					               .status(200)
					               .promptTokens(inputTokens)
					               .completionTokens(outputTokens)
					               .latencyMs(latency)
					               .firstTokenMs(
							               Optional.ofNullable(firstTokenMs)
							                       .map(Long::intValue)
							                       .orElse(null)
					               )
					               .streaming(true)
					               .clientIp(clientIp)
					               .build()
			);
		}
	}

	private String readFirstData(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (!line.startsWith("data: ")) {
				continue;
			}
			String data = line.substring(6).trim();
			if (!data.isEmpty() && !"[DONE]".equals(data)) {
				return data;
			}
		}
		return null;
	}

	private String readFirstSseLine(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.startsWith("event:") || line.startsWith("data:")) {
				return line;
			}
		}
		return null;
	}

	private void handleEmptyStream(
			String accountName,
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			long startTime,
			HttpServletResponse httpRes
	) throws IOException {
		int latency = (int) (System.currentTimeMillis() - startTime);
		String message = "Codex upstream returned an empty SSE stream";
		log.error(LogTag.CODEX + message);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(keyId)
				               .accountId(accountName)
				               .model(model)
				               .requestedModel(model)
				               .status(502)
				               .promptTokens(inputTokens)
				               .latencyMs(latency)
				               .streaming(true)
				               .clientIp(clientIp)
				               .errorMessage(message)
				               .build()
		);
		ErrorResponse.openAi(httpRes, 502, message, "upstream_empty_response");
	}

	private void passThrough(
			CodexHttpClient.CodexResponse response,
			String accountName,
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			long startTime,
			HttpServletResponse httpRes
	) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				response.body()))) {
			String firstLine = readFirstSseLine(reader);
			if (firstLine == null) {
				handleEmptyStream(
						accountName,
						model,
						inputTokens,
						keyId,
						clientIp,
						startTime,
						httpRes
				);
				return;
			}

			httpRes.setCharacterEncoding("UTF-8");
			httpRes.setContentType("text/event-stream; charset=utf-8");
			httpRes.setHeader("Cache-Control", "no-cache");
			PrintWriter writer = httpRes.getWriter();
			long firstTokenMs = System.currentTimeMillis() - startTime;
			writer.write(firstLine);
			writer.write("\n");

			String line;
			while ((line = reader.readLine()) != null) {
				writer.write(line);
				writer.write("\n");
				if (line.isEmpty()) {
					writer.flush();
				}
			}
			writer.flush();

			int latency = (int) (System.currentTimeMillis() - startTime);
			logPublisher.publish(
					RequestLogEvent.builder()
					               .virtualKeyId(keyId)
					               .accountId(accountName)
					               .model(model)
					               .requestedModel(model)
					               .status(200)
					               .promptTokens(inputTokens)
					               .latencyMs(latency)
					               .firstTokenMs((int) firstTokenMs)
					               .streaming(true)
					               .clientIp(clientIp)
					               .build()
			);
		}
	}

	/**
	 * GPT-5 series doesn't support sampling params (temperature, top_p, etc).
	 * Maps temperature → reasoning.effort, max_tokens → max_output_tokens,
	 * then strips unsupported fields.
	 * <p>
	 * temperature → reasoning.effort mapping:
	 * [0.0, 0.3] → "high"
	 * (0.3, 0.7] → "medium"
	 * (0.7, 1.0] → "low"
	 * (1.0, 2.0] → "minimal"
	 *
	 * @see <a href="https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_new_params_and_tools">GPT-5 New Params and Tools</a>
	 * @see <a href="https://developers.openai.com/api/docs/guides/deployment-checklist">API Deployment Checklist — reasoning.effort values</a>
	 * @see <a href="https://help.openai.com/en/articles/5072518">Controlling the length of OpenAI model responses</a>
	 */
	private static void mapSamplingToReasoning(ObjectNode node) {
		if (!node.has("reasoning") && node.has("temperature")) {
			double temp = node.get("temperature").asDouble(1.0);
			String effort;
			if (temp <= 0.3) {
				effort = "high";
			} else if (temp <= 0.7) {
				effort = "medium";
			} else if (temp <= 1.0) {
				effort = "low";
			} else {
				effort = "minimal";
			}
			ObjectNode reasoning = node.putObject("reasoning");
			reasoning.put("effort", effort);
		}
		if (!node.has("max_output_tokens")) {
			if (node.has("max_tokens")) {
				node.set("max_output_tokens", node.get("max_tokens"));
			} else if (node.has("max_completion_tokens")) {
				node.set(
						"max_output_tokens",
						node.get("max_completion_tokens")
				);
			}
		}
		node.remove("temperature");
		node.remove("top_p");
		node.remove("frequency_penalty");
		node.remove("presence_penalty");
		node.remove("logit_bias");
		node.remove("logprobs");
		node.remove("top_logprobs");
		node.remove("n");
		node.remove("max_tokens");
		node.remove("max_completion_tokens");
		node.remove("thinking"); // Anthropic-only; Codex uses reasoning.effort
	}
}
