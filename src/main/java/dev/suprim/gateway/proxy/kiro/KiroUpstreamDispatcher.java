package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.kiro.payload.PayloadBuilder;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class KiroUpstreamDispatcher {

	private static final List<KiroEndpoint> ENDPOINTS = List.of(
			new KiroEndpoint(
					"https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse",
					"AmazonCodeWhispererStreamingService.GenerateAssistantResponse",
					"CodeWhisperer"
			),
			new KiroEndpoint(
					"https://q.us-east-1.amazonaws.com/generateAssistantResponse",
					"",
					"Kiro IDE"
			),
			new KiroEndpoint(
					"https://q.us-east-1.amazonaws.com/generateAssistantResponse",
					"AmazonQDeveloperStreamingService.SendMessage",
					"AmazonQ"
			)
	);

	private final KiroHttpClient kiroClient;
	private final PayloadBuilder payloadBuilder;
	private final KiroAuthManager auth;
	private final AccountRotator accountRotator;
	private final CredentialStore credentialStore;

	public KiroResponse dispatch(
			InternalRequest request,
			boolean stream
	) throws Exception {
		List<StoredAccount> accounts = credentialStore.findAllByProvider(
				Provider.KIRO.name()
		);
		if (accounts.size() <= 1) {
			return dispatchSingle(request, stream);
		}
		return dispatchWithRotation(request, stream, accounts);
	}

	private KiroResponse dispatchWithRotation(
			InternalRequest request,
			boolean stream,
			List<StoredAccount> accounts
	) throws Exception {
		String payload = payloadBuilder.buildOpenAiPayload(request, auth);
		int maxAttempts = accounts.size();

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			StoredAccount account = accountRotator.next(Provider.KIRO.name());
			String accessToken;
			try {
				accessToken = auth.getAccessToken(account);
			} catch (Exception e) {
				log.warn(
						LogTag.KIRO + "Auth failed for {}: {}",
						account.name(),
						e.getMessage()
				);
				continue;
			}

			log.info(
					LogTag.KIRO + "Using account: {} (attempt {}/{})",
					account.name(), attempt + 1, maxAttempts
			);

			KiroResponse result;

			try {
				result = tryAllEndpoints(payload, stream, accessToken, account);
			} catch (Exception e) {
				log.error(
						LogTag.KIRO + "Request failed for {}: {}",
						account.name(),
						e.getMessage()
				);
				continue;
			}

			if (result != null) {
				log.info(LogTag.KIRO + "Response served by account: {}", account.name());
				return result;
			}

			// all endpoints 403 → refresh token and retry once
			log.info(LogTag.KIRO + "All endpoints 403 for {}, refreshing token", account.name());
			try {
				auth.forceRefresh();
				accessToken = auth.getAccessToken(account);
			} catch (Exception e) {
				log.warn(LogTag.KIRO + "Refresh failed for {}: {}", account.name(), e.getMessage());
				continue;
			}

			result = tryAllEndpoints(payload, stream, accessToken, account);
			if (result != null) {
				log.info(LogTag.KIRO + "Response served by account: {} (after refresh)", account.name());
				return result;
			}
		}
		throw new RuntimeException("All Kiro accounts exhausted");
	}

	private KiroResponse tryAllEndpoints(String payload, boolean stream, String accessToken, StoredAccount account) throws Exception {
		boolean isApiKey = "api_key".equalsIgnoreCase(account.authType());
		for (KiroEndpoint ep : ENDPOINTS) {
			String amzTarget = ep.amzTarget().isEmpty() ? null : ep.amzTarget();
			KiroResponse response = kiroClient.request(
					"POST", ep.url(), payload, stream, accessToken, amzTarget, isApiKey
			);
			if (response.status() == 200) {
				return response;
			}
			if (response.status() == 403) {
				log.warn(LogTag.KIRO + "403 from {}, trying next endpoint", ep.name());
				continue;
			}
			if (response.status() == 429 || response.status() == 503) {
				log.warn(LogTag.KIRO + "Account {} got {} from {}, trying next account",
						account.name(), response.status(), ep.name());
				try (InputStream is = response.body()) {
					is.readAllBytes();
				}
				return null;
			}
			return response;
		}
		return null;
	}

	private KiroResponse dispatchSingle(
			InternalRequest request,
			boolean stream
	) throws Exception {
		String payload = payloadBuilder.buildOpenAiPayload(request, auth);
		String accessToken = auth.getAccessToken();
		log.debug(
				LogTag.KIRO + "Streaming payload (first 500): {}",
				payload.substring(0, Math.min(500, payload.length()))
		);

		for (KiroEndpoint ep : ENDPOINTS) {
			String amzTarget = ep.amzTarget().isEmpty() ? null : ep.amzTarget();
			KiroResponse response = kiroClient.request(
					"POST", ep.url(), payload, stream, accessToken, amzTarget
			);
			if (response.status() == 200) {
				return response;
			}
			if (response.status() == 403) {
				log.warn(LogTag.KIRO + "403 from {}, trying next endpoint", ep.name());
				continue;
			}
			return response;
		}
		throw new RuntimeException("All Kiro endpoints failed");
	}

	private record KiroEndpoint(String url, String amzTarget, String name) {}
}
