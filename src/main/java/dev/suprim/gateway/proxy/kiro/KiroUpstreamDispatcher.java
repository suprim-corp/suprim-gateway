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
import dev.suprim.gateway.provider.kiro.payload.KiroPayloadDiagnostics;
import dev.suprim.gateway.provider.kiro.payload.PayloadBuilder;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class KiroUpstreamDispatcher {

	/**
	 * Upstream statuses that mean this account cannot serve the request but another might:
	 * a rate limit (429/503) or an exhausted quota / payment required (402). Any of them
	 * rotates to the next account rather than refreshing this account's token.
	 */
	private static final Set<Integer> ROTATE_ACCOUNT_STATUSES = Set.of(402, 429, 503);

	private static final KiroEndpoint RUNTIME = new KiroEndpoint(
			Kiro.RUNTIME_HOST + Kiro.GENERATE_PATH,
			"Kiro Runtime"
	);
	private static final KiroEndpoint CODE_WHISPERER = new KiroEndpoint(
			Kiro.CODEWHISPERER_HOST + Kiro.GENERATE_PATH,
			"CodeWhisperer"
	);
	private static final KiroEndpoint AMAZON_Q = new KiroEndpoint(
			Kiro.Q_HOST + Kiro.GENERATE_PATH,
			"AmazonQ"
	);

	/**
	 * Kiro OIDC and social logins (builder-id, Google, GitHub): the kiro.dev gateway is the
	 * surface built for their tokens, so it stays first.
	 */
	private static final List<KiroEndpoint> OAUTH_ENDPOINTS = List.of(
			RUNTIME,
			CODE_WHISPERER,
			AMAZON_Q
	);

	/**
	 * AWS SSO access tokens — IAM Identity Center, enterprise external IdP and API keys.
	 * <p>
	 * The kiro.dev gateway only accepts Kiro OIDC/social tokens and rejects this family outright,
	 * and CodeWhisperer authenticates the token but answers {@code REQUEST_BODY_INVALID} to a
	 * payload the Q surface accepts. Q therefore goes first and kiro.dev is kept as a last resort.
	 */
	private static final List<KiroEndpoint> AWS_SSO_ENDPOINTS = List.of(
			AMAZON_Q,
			CODE_WHISPERER,
			RUNTIME
	);

	/** Auth types whose stored token is an AWS SSO access token rather than a Kiro OIDC one. */
	private static final Set<String> AWS_SSO_AUTH_TYPES = Set.of(
			"aws_sso_oidc",
			"idc",
			"external_idp",
			"api_key"
	);

	/**
	 * Ceiling on the whole rotation. Each account can spend seconds on 429/5xx backoff across
	 * three endpoints, so an unbounded loop leaves the client waiting minutes for a request that
	 * every account has already declined. The heartbeat holds the connection open regardless;
	 * this decides when waiting longer stops being worth it.
	 */
	private static final Duration ROTATION_BUDGET = Duration.ofSeconds(90);

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
		long deadline = System.currentTimeMillis() + ROTATION_BUDGET.toMillis();

		for (int attempt = 0; !remainingAccounts.isEmpty(); attempt++) {
			if (System.currentTimeMillis() > deadline) {
				log.warn(
						LogTag.KIRO +
						"Rotation budget spent after {} accounts, giving up",
						attempt
				);
				break;
			}
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
				InspectedResponse inspected = inspectInvalidModelResponse(
						response,
						payload,
						endpointAttempt.endpoint(),
						account.name()
				);
				response = inspected.response();
				if (inspected.invalidModel()) {
					invalidModelResult = new DispatchResult(
							response,
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
				if (ROTATE_ACCOUNT_STATUSES.contains(response.status())) {
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
	private record EndpointAttempt(
			KiroResponse response,
			boolean tokenRejected,
			String endpoint
	) {

		static EndpointAttempt served(KiroResponse response, String endpoint) {
			return new EndpointAttempt(response, false, endpoint);
		}

		static EndpointAttempt rateLimited() {
			return new EndpointAttempt(null, false, null);
		}

		static EndpointAttempt rejectedToken() {
			return new EndpointAttempt(null, true, null);
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

	/**
	 * The endpoints to try for one account, ordered by the surface its token is accepted on.
	 *
	 * @see #AWS_SSO_ENDPOINTS
	 */
	private static List<KiroEndpoint> endpointsFor(StoredAccount account) {
		String authType = account.authType();
		return authType != null &&
		       AWS_SSO_AUTH_TYPES.contains(authType.trim()
		                                           .toLowerCase(Locale.ROOT))
				? AWS_SSO_ENDPOINTS
				: OAUTH_ENDPOINTS;
	}

	private EndpointAttempt tryAllEndpoints(
			String payload,
			boolean stream,
			String accessToken,
			StoredAccount account
	) throws Exception {
		boolean isApiKey = "api_key".equalsIgnoreCase(account.authType());
		List<KiroEndpoint> endpoints = endpointsFor(account);
		String accountKey = Optional.ofNullable(account.name())
		                            .orElseGet(() ->
				                            account.accessToken()
				                                   .substring(0, 8)
		                            );
		Integer preferred = preferredEndpoint.get(accountKey);

		if (preferred != null) {
			KiroEndpoint ep = endpoints.get(preferred);
			KiroResponse response = null;
			try {
				response = kiroClient.request(
						"POST",
						ep.url(),
						payload,
						stream,
						accessToken,
						isApiKey
				);
			} catch (Exception e) {
				log.warn(
						LogTag.KIRO +
						"{} from preferred {}, falling back to all endpoints: {}",
						e.getClass().getSimpleName(),
						ep.name(),
						e.getMessage()
				);
				preferredEndpoint.remove(accountKey);
			}
			if (response != null) {
				if (response.status() == 200) {
					return EndpointAttempt.served(response, ep.name());
				}
				if (ROTATE_ACCOUNT_STATUSES.contains(response.status())) {
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
		}

		for (int i = 0; i < endpoints.size(); i++) {
			KiroEndpoint ep = endpoints.get(i);
			KiroResponse response;
			try {
				response = kiroClient.request(
						"POST",
						ep.url(),
						payload,
						stream,
						accessToken,
						isApiKey
				);
			} catch (Exception e) {
				log.warn(
						LogTag.KIRO + "Error from {} ({}): {}",
						ep.name(),
						ep.url(),
						e.getMessage()
				);
				continue;
			}
			if (response.status() == 200) {
				preferredEndpoint.put(accountKey, i);
				return EndpointAttempt.served(response, ep.name());
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
			if (ROTATE_ACCOUNT_STATUSES.contains(response.status())) {
				log.warn(
						LogTag.KIRO +
						"Account {} got {} from {}, trying next account",
						account.name(), response.status(), ep.name()
				);
				drain(response.body());
				return EndpointAttempt.rateLimited();
			}
			return EndpointAttempt.served(response, ep.name());
		}
		return EndpointAttempt.rejectedToken();
	}

	private InspectedResponse inspectInvalidModelResponse(
			KiroResponse response,
			String payload,
			String endpoint,
			String account
	) throws Exception {
		if (response.status() != 400) {
			return new InspectedResponse(response, false);
		}

		byte[] error;
		try (InputStream body = response.body()) {
			error = body.readAllBytes();
		}
		KiroResponse replayableResponse = KiroResponse.builder()
		                                               .status(response.status())
		                                               .body(new ByteArrayInputStream(error))
		                                               .contentType(response.contentType())
		                                               .build();
		String errorBody = new String(error, StandardCharsets.UTF_8);
		JsonNode parsedError = parseError(errorBody);
		String reason = parsedError.path("reason").asString();
		if ("REQUEST_BODY_INVALID".equals(reason)) {
			KiroPayloadDiagnostics.logInvalidRequest(
					payload,
					endpoint,
					account,
					reason,
					parsedError.path("message").asString()
			);
		}
		return new InspectedResponse(
				replayableResponse,
				"INVALID_MODEL_ID".equals(reason)
		);
	}

	private static JsonNode parseError(String body) {
		try {
			return new JsonMapper().readTree(body);
		} catch (Exception ignored) {
			return MissingNode.getInstance();
		}
	}

	private record InspectedResponse(KiroResponse response, boolean invalidModel) {}

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

	private record KiroEndpoint(String url, String name) {}
}
