package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.kiro.payload.PayloadBuilder;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class KiroUpstreamDispatcher {

	private static final List<KiroEndpoint> ENDPOINTS = List.of(
			new KiroEndpoint(
					Kiro.CODEWHISPERER_HOST + Kiro.GENERATE_PATH,
					Kiro.AMZ_TARGET,
					"CodeWhisperer"
			),
			new KiroEndpoint(
					Kiro.Q_HOST + Kiro.GENERATE_PATH,
					"",
					"Kiro IDE"
			),
			new KiroEndpoint(
					Kiro.Q_HOST + Kiro.GENERATE_PATH,
					Kiro.AMZ_TARGET_Q,
					"AmazonQ"
			)
	);

	private final KiroHttpClient kiroClient;
	private final PayloadBuilder payloadBuilder;
	private final KiroAuthManager auth;
	private final AccountRotator accountRotator;
	private final CredentialStore credentialStore;
	private final KiroAccountModelAvailability modelAvailability;
	private final ModelResolver modelResolver;
	private final ConcurrentHashMap<String, Integer> preferredEndpoint = new ConcurrentHashMap<>();

	public record DispatchResult(KiroResponse response, String accountId) {}

	public DispatchResult dispatch(
			InternalRequest request,
			boolean stream
	) throws Exception {
		List<StoredAccount> accounts = credentialStore.findAllByProvider(
				Provider.KIRO.name()
		);
		String model = modelResolver.canonicalize(request.model());
		List<StoredAccount> eligibleAccounts = modelAvailability.eligibleAccounts(
				model,
				accounts
		);
		if (eligibleAccounts.isEmpty()) {
			if (modelAvailability.isWarmUpComplete(accounts)) {
				return new DispatchResult(
						KiroResponse.builder()
						            .status(400)
						            .body(new ByteArrayInputStream(
										            "{\"message\":\"Invalid model. Please select a different model to continue.\",\"reason\":\"INVALID_MODEL_ID\"}"
												            .getBytes(StandardCharsets.UTF_8)
								            )
						            )
						            .contentType("application/json")
						            .build(),
						null
				);
			}
			throw new RuntimeException("Kiro model availability is warming up");
		}
		return dispatchWithRotation(request, stream, model, eligibleAccounts);
	}

	private DispatchResult dispatchWithRotation(
			InternalRequest request,
			boolean stream,
			String model,
			List<StoredAccount> accounts
	) throws Exception {
		List<StoredAccount> remainingAccounts = new ArrayList<>(
				accounts.stream()
				        .collect(
						        Collectors.toMap(
								        KiroAccountModelAvailability::accountKey,
								        account -> account,
								        (first, ignored) -> first,
								        LinkedHashMap::new
						        )
				        )
				        .values()
		);
		int maxAttempts = remainingAccounts.size();
		DispatchResult invalidModelResult = null;

		for (int attempt = 0; !remainingAccounts.isEmpty(); attempt++) {
			StoredAccount account = accountRotator.next(
					Provider.KIRO.name(),
					List.copyOf(remainingAccounts)
			);
			remainingAccounts.remove(account);
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

			// Rebuilt per account: the payload carries that account's own profile ARN, and an
			// ARN from a different account makes the upstream reject the bearer token.
			String payload = payloadBuilder.buildOpenAiPayload(
					request,
					profileArnFor(account)
			);

			EndpointAttempt endpointAttempt;
			try {
				endpointAttempt = tryAllEndpoints(
						payload,
						stream,
						accessToken,
						account
				);
			} catch (Exception e) {
				log.error(
						LogTag.KIRO + "Request failed for {}: {}",
						account.name(),
						e.getMessage()
				);
				continue;
			}

			KiroResponse response = endpointAttempt.response();
			if (response != null) {
				KiroResponse invalidModel = copyInvalidModelResponse(response);
				if (invalidModel != null) {
					invalidModelResult = new DispatchResult(
							invalidModel,
							account.name()
					);
					modelAvailability.invalidateModel(account, model);
					log.warn(
							LogTag.KIRO +
							"Account {} rejected the model, trying next account",
							account.name()
					);
					continue;
				}
				if (response.status() == 429 || response.status() == 503) {
					log.warn(
							LogTag.KIRO +
							"Account {} got {}, trying next account",
							account.name(), response.status()
					);
					continue;
				}
				log.info(
						LogTag.KIRO + "Response served by account: {}",
						account.name()
				);
				return new DispatchResult(response, account.name());
			}

			// a rate-limited account needs a different account, not a new token
			if (!endpointAttempt.tokenRejected()) {
				continue;
			}

			// all endpoints 403 → refresh token and retry once
			log.info(
					LogTag.KIRO + "All endpoints 403 for {}, refreshing token",
					account.name()
			);
			try {
				accessToken = auth.forceRefresh(account);
			} catch (Exception e) {
				log.warn(
						LogTag.KIRO + "Refresh failed for {}: {}",
						account.name(),
						e.getMessage()
				);
				continue;
			}

			response = tryAllEndpoints(payload, stream, accessToken, account)
					.response();
			if (response != null) {
				log.info(
						LogTag.KIRO +
						"Response served by account: {} (after refresh)",
						account.name()
				);
				return new DispatchResult(response, account.name());
			}
		}
		if (invalidModelResult != null) {
			return invalidModelResult;
		}
		throw new RuntimeException("All Kiro accounts exhausted");
	}

	/**
	 * Outcome of trying the Kiro endpoints for one account. A missing response
	 * means the account produced no usable answer; {@code tokenRejected} tells
	 * whether refreshing its token is worth trying, as opposed to the account
	 * being rate limited and needing rotation.
	 */
	private record EndpointAttempt(KiroResponse response, boolean tokenRejected) {

		static EndpointAttempt served(KiroResponse response) {
			return new EndpointAttempt(response, false);
		}

		static EndpointAttempt rateLimited() {
			return new EndpointAttempt(null, false);
		}

		static EndpointAttempt rejectedToken() {
			return new EndpointAttempt(null, true);
		}
	}

	/**
	 * The profile ARN to send as one account, or null when it needs none.
	 * <p>
	 * An API-key account is already scoped by its key and must not send one. Every other account
	 * sends its own stored ARN — never the connected account's, since an ARN belonging to a
	 * different account makes the upstream reject the token.
	 */
	private static String profileArnFor(StoredAccount account) {
		return "api_key".equalsIgnoreCase(account.authType())
				? null
				: account.profileArn();
	}

	private EndpointAttempt tryAllEndpoints(
			String payload,
			boolean stream,
			String accessToken,
			StoredAccount account
	) throws Exception {
		boolean isApiKey = "api_key".equalsIgnoreCase(account.authType());
		String accountKey = Optional.ofNullable(account.name())
		                            .orElseGet(() ->
				                            account.accessToken()
				                                   .substring(0, 8)
		                            );
		Integer preferred = preferredEndpoint.get(accountKey);

		if (preferred != null) {
			KiroEndpoint ep = ENDPOINTS.get(preferred);
			String amzTarget = ep.amzTarget().isEmpty() ? null : ep.amzTarget();
			KiroResponse response = kiroClient.request(
					"POST",
					ep.url(),
					payload,
					stream,
					accessToken,
					amzTarget,
					isApiKey
			);
			if (response.status() == 200) {
				return EndpointAttempt.served(response);
			}
			if (response.status() == 429 || response.status() == 503) {
				log.warn(
						LogTag.KIRO +
						"Account {} got {} from {}, trying next account",
						account.name(), response.status(), ep.name()
				);
				drain(response.body());
				return EndpointAttempt.rateLimited();
			}
			log.warn(
					LogTag.KIRO +
					"{} from preferred {}, falling back to all endpoints",
					response.status(),
					ep.name()
			);
			preferredEndpoint.remove(accountKey);
		}

		for (int i = 0; i < ENDPOINTS.size(); i++) {
			KiroEndpoint ep = ENDPOINTS.get(i);
			String amzTarget = ep.amzTarget().isEmpty() ? null : ep.amzTarget();
			KiroResponse response = kiroClient.request(
					"POST",
					ep.url(),
					payload,
					stream,
					accessToken,
					amzTarget,
					isApiKey
			);
			if (response.status() == 200) {
				preferredEndpoint.put(accountKey, i);
				return EndpointAttempt.served(response);
			}
			if (response.status() == 403) {
				log.warn(
						LogTag.KIRO + "403 from {} ({}): {}",
						ep.name(),
						ep.url(),
						readBody(response)
				);
				continue;
			}
			if (response.status() == 429 || response.status() == 503) {
				log.warn(
						LogTag.KIRO +
						"Account {} got {} from {}, trying next account",
						account.name(), response.status(), ep.name()
				);
				drain(response.body());
				return EndpointAttempt.rateLimited();
			}
			return EndpointAttempt.served(response);
		}
		return EndpointAttempt.rejectedToken();
	}

	private KiroResponse copyInvalidModelResponse(KiroResponse response) throws Exception {
		if (response.status() != 400) {
			return null;
		}

		try (InputStream body = response.body()) {
			byte[] error = body.readAllBytes();
			if (!new String(error, StandardCharsets.UTF_8).contains(
					"\"reason\":\"INVALID_MODEL_ID\"")) {
				return null;
			}
			return KiroResponse.builder()
			                   .status(response.status())
			                   .body(new ByteArrayInputStream(error))
			                   .contentType(response.contentType())
			                   .build();
		}
	}

	private void drain(InputStream body) throws Exception {
		try (body) {
			body.readAllBytes();
		}
	}

	/**
	 * Reads and closes an error body so it can be logged. Returns a placeholder rather than
	 * throwing: this only runs on a path that is already failing, and losing the reason to a
	 * secondary failure is worse than an imprecise log line.
	 */
	private String readBody(KiroResponse response) {
		try (InputStream body = response.body()) {
			return new String(body.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "<unreadable: " + e.getMessage() + ">";
		}
	}

	private record KiroEndpoint(String url, String amzTarget, String name) {}
}
