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
import dev.suprim.gateway.proxy.OpenAiRelayHandler;
import dev.suprim.gateway.proxy.ProxyChain;
import dev.suprim.gateway.utils.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Component
public class CodexFacade {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final CodexAuthManager authManager;
	private final RequestLogPublisher logPublisher;
	private final OpenAiRelayHandler relayHandler;
	private final AccountRotator accountRotator;
	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;

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

		String payload = MAPPER.writeValueAsString(request);

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			StoredAccount account = accountRotator.next(Provider.CODEX.name());
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

			CodexHttpClient.CodexResponse response = CodexHttpClient.call(
					payload,
					accessToken,
					proxyChain
			);
			log.info(
					LogTag.CODEX + "Upstream responded with status {}",
					response.status()
			);

			if (response.status() == 429 || response.status() == 503) {
				log.warn(
						LogTag.CODEX + "Account {} got {}, trying next",
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

			// Codex upstream returns Responses API SSE — pass-through directly
			relayUpstream(
					response, account.name(), model, inputTokens,
					keyId, clientIp, startTime, format, httpRes
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
			HttpServletResponse httpRes
	) throws Exception {
		if (format == Format.COMPLETION) {
			// Client wants OpenAI chat/completions format — use relay handler to convert
			OpenAiRelayHandler.StreamResult result = relayHandler.relayStream(
					response.body(),
					format,
					model,
					inputTokens,
					startTime,
					httpRes
			);
			int latency = (int) (System.currentTimeMillis() - startTime);
			logPublisher.publish(
					RequestLogEvent.builder()
					               .virtualKeyId(keyId)
					               .accountId(accountName)
					               .model(model)
					               .requestedModel(model)
					               .status(200)
					               .promptTokens(result.promptTokens())
					               .completionTokens(result.completionTokens())
					               .latencyMs(latency)
					               .firstTokenMs(
							               Optional.ofNullable(
									                       result.firstTokenMs())
							                       .map(Long::intValue)
							                       .orElse(null)
					               )
					               .streaming(true)
					               .clientIp(clientIp)
					               .build()
			);
		} else {
			// Responses or Anthropic format — pass-through the SSE as-is
			// (upstream already returns Responses API SSE)
			httpRes.setCharacterEncoding("UTF-8");
			httpRes.setContentType("text/event-stream; charset=utf-8");
			httpRes.setHeader("Cache-Control", "no-cache");
			PrintWriter writer = httpRes.getWriter();

			Long firstTokenMs = null;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
					response.body()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (firstTokenMs == null && (line.startsWith("event:") ||
					                             line.startsWith("data:"))) {
						firstTokenMs = System.currentTimeMillis() - startTime;
					}
					writer.write(line);
					writer.write("\n");
					if (line.isEmpty()) {
						writer.flush();
					}
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
					               .firstTokenMs(
							               Optional.ofNullable(
									                       firstTokenMs)
							                       .map(Long::intValue)
							                       .orElse(null)
					               )
					               .streaming(true)
					               .clientIp(clientIp)
					               .build());
		}
	}
}
