package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tries the connected Antigravity accounts in rotation until one answers, so
 * {@link AntigravityFacade} deals with a single outcome rather than the rotation.
 * <p>
 * Each account is tried at most once per request. A rate limit or a rejection moves on to
 * the next account; any other error stops the rotation, since repeating it would only get
 * the same answer from every account.
 */
@Slf4j
@RequiredArgsConstructor
@Component
class AntigravityAccountAttempts {

	private static final Set<Integer> RATE_LIMIT_STATUSES = Set.of(429, 503);
	private static final Set<Integer> COOLDOWN_STATUSES = Set.of(429, 503, 403);
	private static final Set<Integer> ROTATE_ONLY_STATUSES = Set.of(401);

	private final AntigravityAuthManager authManager;
	private final AccountRotator accountRotator;
	private final AntigravityAccountCooldown accountCooldown;

	/**
	 * How the rotation ended. Exactly one of {@code response} or {@code failure} is set:
	 * a {@code response} is an upstream 200 whose body the caller now owns and must close;
	 * a {@code failure} is the error worth reporting, or null when every account was merely
	 * rate-limited and there is nothing specific to relay.
	 */
	@Builder
	record Outcome(
			AntigravityHttpClient.AntigravityResponse response,
			String accountName,
			Failure failure
	) {

		boolean succeeded() {
			return response != null;
		}
	}

	/** An upstream error, kept with the account that produced it for the request log. */
	@Builder
	record Failure(int status, String body, String accountName) {}

	/**
	 * @param payloadFor builds the request body for one account, since the body carries that
	 *                   account's project id
	 */
	Outcome run(
			List<StoredAccount> accounts,
			String model,
			PayloadFactory payloadFor
	) throws Exception {
		Failure lastFailure = null;
		Set<String> attempted = new HashSet<>();
		int maxAttempts = accounts.size();

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			StoredAccount account = accountRotator.next(Provider.ANTIGRAVITY.name());
			if (!attempted.add(accountCooldown.accountKey(account))
			    || accountCooldown.isCoolingDown(account)) {
				continue;
			}

			String accessToken;
			try {
				accessToken = authManager.getAccessToken(account);
			} catch (Exception e) {
				log.error(
						LogTag.ANTIGRAVITY + "Auth failed for {}: {}",
						account.name(),
						e.getMessage()
				);
				continue;
			}

			log.info(
					LogTag.ANTIGRAVITY + "Using account: {} (attempt {}/{})",
					account.name(), attempt + 1, maxAttempts
			);

			AntigravityHttpClient.AntigravityResponse response =
					AntigravityHttpClient.streamGenerateContent(
							model,
							payloadFor.build(authManager.getProjectId(account)),
							accessToken
					);

			if (response.status() == 200) {
				return Outcome.builder()
				              .response(response)
				              .accountName(account.name())
				              .build();
			}

			String body = drain(response);
			int status = response.status();

			// A rate limit says nothing about the request itself, so it is not worth
			// reporting when a later account fails for a real reason.
			if (!RATE_LIMIT_STATUSES.contains(status)) {
				lastFailure = Failure.builder()
				                     .status(status)
				                     .body(body)
				                     .accountName(account.name())
				                     .build();
			}

			if (COOLDOWN_STATUSES.contains(status)) {
				accountCooldown.coolDown(account);
				log.warn(
						LogTag.ANTIGRAVITY + "Account {} got {}, cooling down for 1h: {}",
						account.name(), status, body
				);
				continue;
			}

			if (ROTATE_ONLY_STATUSES.contains(status)) {
				log.warn(
						LogTag.ANTIGRAVITY + "Account {} unauthorized, trying next account: {}",
						account.name(), body
				);
				continue;
			}

			return Outcome.builder()
			              .failure(Failure.builder()
			                              .status(status)
			                              .body(body)
			                              .accountName(account.name())
			                              .build())
			              .build();
		}

		return Outcome.builder().failure(lastFailure).build();
	}

	/** Builds the request body for the account whose project id is passed in. */
	@FunctionalInterface
	interface PayloadFactory {

		String build(String projectId);
	}

	private static String drain(AntigravityHttpClient.AntigravityResponse response)
			throws Exception {
		try (InputStream body = response.body()) {
			return new String(body.readAllBytes());
		}
	}
}
