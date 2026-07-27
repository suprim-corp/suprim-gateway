package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.refresher.DesktopTokenRefresher;
import dev.suprim.gateway.provider.kiro.refresher.RefreshResult;
import dev.suprim.gateway.provider.kiro.refresher.SsoOidcTokenRefresher;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;

/**
 * Exchanges one account's refresh token for a fresh access token, picking the flow its auth type
 * requires.
 * <p>
 * Reports failure as null rather than throwing: with several accounts configured the caller
 * rotates to the next one, so a single account's expired registration must not abort the request.
 */
@Slf4j
final class KiroAccountRefresher {

	private static final String DESKTOP_AUTH_TYPE = "KIRO_DESKTOP";

	private KiroAccountRefresher() {}

	/**
	 * The account with refreshed tokens, or null when it has no refresh token or the upstream
	 * rejected it. The previous refresh token is kept when the upstream does not rotate it.
	 */
	static StoredAccount refresh(StoredAccount account, HttpClient httpClient) {
		String refreshToken = account.refreshToken();
		if (refreshToken == null) {
			return null;
		}
		try {
			RefreshResult result = DESKTOP_AUTH_TYPE.equals(account.authType())
					? DesktopTokenRefresher.refresh(
					refreshToken,
					account.region(),
					httpClient
			)
					: SsoOidcTokenRefresher.refresh(
					refreshToken,
					account.clientId(),
					account.clientSecret(),
					account.scopes(),
					account.region(),
					httpClient
			);
			log.info(
					LogTag.KIRO + "On-demand refresh succeeded for {}",
					account.name()
			);
			return account.withTokens(
					result.accessToken(),
					Optional.ofNullable(result.refreshToken())
					        .orElse(refreshToken),
					result.expiresAt()
			);
		} catch (Exception e) {
			log.warn(
					LogTag.KIRO + "On-demand refresh failed for {}: {}",
					account.name(),
					e.getMessage()
			);
			return null;
		}
	}

	/**
	 * Cached token state for one account. Treated as expired ten minutes early so a request does
	 * not start with a token that lapses mid-flight.
	 */
	record TokenState(
			String accessToken,
			String refreshToken,
			Instant expiresAt
	) {

		static TokenState of(StoredAccount account) {
			return new TokenState(
					account.accessToken(),
					account.refreshToken(),
					account.expiresAt()
			);
		}

		boolean isExpired() {
			return expiresAt == null ||
			       Instant.now().isAfter(expiresAt.minusSeconds(600));
		}
	}
}
