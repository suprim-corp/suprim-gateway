package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.provider.AccountCooldown;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.ProxyChain;
import dev.suprim.gateway.utils.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
@Component
public class CodexFacade {

	private static final JsonMapper MAPPER = new JsonMapper();
	private final CodexAuthManager authManager;
	private final AccountRotator accountRotator;
	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;
	private final AccountCooldown accountCooldown;
	private final CodexResponseRelay responseRelay;

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
		String payload = MAPPER.writeValueAsString(
				CodexRequestConverter.toPayload(model, request)
		);

		Set<String> attemptedAccounts = new HashSet<>();
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			StoredAccount account = accountRotator.next(Provider.CODEX.name());
			String accountKey = accountCooldown.accountKey(account);
			if (!attemptedAccounts.add(accountKey) ||
			    accountCooldown.isCoolingDown(account)) {
				continue;
			}
			String accessToken;
			try {
				accessToken = authManager.getAccessToken(account);
			} catch (Exception exception) {
				log.error(
						LogTag.CODEX + "Auth failed for {}: {}",
						account.name(), exception.getMessage()
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
			} catch (IOException exception) {
				log.warn(
						LogTag.CODEX + "Upstream request failed: {}",
						exception.getMessage()
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
						LogTag.CODEX + "Account {} got {}, cooling down for {}",
						account.name(),
						response.status(),
						AccountCooldown.duration()
				);
				try (InputStream input = response.body()) {
					input.readAllBytes();
				}
				continue;
			}
			if (response.status() == 401) {
				log.warn(
						LogTag.CODEX +
						"Account {} unauthorized, trying next account",
						account.name()
				);
				try (InputStream input = response.body()) {
					input.readAllBytes();
				}
				continue;
			}

			CodexResponseRelay.Call call =
					CodexResponseRelay.Call.builder()
					                       .accountName(account.name())
					                       .model(model)
					                       .inputTokens(inputTokens)
					                       .keyId(keyId)
					                       .clientIp(clientIp)
					                       .startTime(startTime)
					                       .format(format)
					                       .requestThinkingEnabled(
							                       request.thinkingEnabled()
					                       )
					                       .httpRes(httpRes)
					                       .build();
			if (response.status() != 200) {
				responseRelay.handleError(response, call);
				return;
			}

			try {
				responseRelay.relay(response, call);
				return;
			} catch (CodexResponseRelay.ServerOverloadedException exception) {
				accountCooldown.coolDown(account);
				log.warn(
						LogTag.CODEX +
						"Account {} server overloaded, trying another account",
						account.name()
				);
			}
		}

		ErrorResponse.openAi(
				httpRes,
				429,
				"All accounts rate-limited",
				"rate_limit_exhausted"
		);
	}
}
