package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.reader.CredentialStoreReader;
import dev.suprim.gateway.provider.kiro.refresher.DesktopTokenRefresher;
import dev.suprim.gateway.provider.kiro.refresher.RefreshResult;
import dev.suprim.gateway.provider.kiro.refresher.SsoOidcTokenRefresher;
import dev.suprim.gateway.proxy.ProxyChain;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single account the gateway reports as "connected", kept alongside the multi-account token
 * cache.
 * <p>
 * This is the pre-multi-account model: one set of credentials loaded from the store at startup and
 * refreshed in place. It still backs the connection status shown in the UI and the config-driven
 * single-account setup, so it is held here rather than folded into the per-account path.
 */
@Slf4j
@RequiredArgsConstructor
class KiroPrimaryAccount {

	private static final long REFRESH_COOLDOWN_MS = 60_000;

	/**
	 * Refresh this long before expiry, so a request does not start on a lapsing token.
	 */
	private static final long EXPIRY_MARGIN_SECONDS = 600;

	private final AppConfig config;
	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;
	private final ReentrantLock refreshLock = new ReentrantLock();

	private String accessToken;
	private String refreshToken;
	private Instant expiresAt;
	@Getter
	private String profileArn;
	private String accountName;
	private String clientId;
	private String clientSecret;
	private String[] scopes;
	@Getter
	private KiroCredentials.AuthType authType = KiroCredentials.AuthType.KIRO_DESKTOP;
	private long lastRefreshFailure;

	/**
	 * Loads credentials from the store, resolving the profile ARN when an API key was configured
	 * without one. A store with no Kiro credentials leaves this account disconnected.
	 */
	void load() {
		String configuredArn = config.profileArn();
		boolean blankArn = configuredArn == null || configuredArn.isBlank();
		this.profileArn = blankArn ? null : configuredArn;

		Optional<KiroCredentials> stored =
				CredentialStoreReader.read(credentialStore);
		if (stored.isEmpty()) {
			return;
		}
		apply(stored.get());
		if (blankArn && authType == KiroCredentials.AuthType.API_KEY) {
			resolveProfileArn();
		}
		loadAccountName();
	}

	/**
	 * The current token, refreshing first when it is expired. API keys never expire.
	 */
	String accessToken() throws Exception {
		if (authType == KiroCredentials.AuthType.API_KEY || isTokenFresh()) {
			return accessToken;
		}
		refresh();
		return accessToken;
	}

	/**
	 * Refreshes unconditionally, unless this account authenticates with an API key.
	 */
	void forceRefresh() throws Exception {
		if (authType != KiroCredentials.AuthType.API_KEY) {
			refresh();
		}
	}

	boolean isApiKeyAuth() {
		return authType == KiroCredentials.AuthType.API_KEY;
	}

	boolean isConnected() {
		return accessToken != null && refreshToken != null;
	}

	String displayName() {
		return accountName != null ? accountName : profileArn;
	}

	void disconnect() {
		this.accessToken = null;
		this.refreshToken = null;
		this.expiresAt = null;
	}

	/**
	 * Adopts an imported account's credentials as the primary ones. Called when the import is
	 * this installation's only account, or replaces the one already held.
	 */
	void adopt(StoredAccount account) {
		this.accessToken = account.accessToken();
		this.refreshToken = account.refreshToken();
		this.expiresAt = account.expiresAt();
		this.clientId = account.clientId();
		this.clientSecret = account.clientSecret();
		this.authType = KiroCredentials.AuthType.valueOf(account.authType());
		if (account.profileArn() != null) {
			this.profileArn = account.profileArn();
		}
		loadAccountName();
	}

	private boolean isTokenFresh() {
		return accessToken != null && expiresAt != null &&
		       Instant.now()
		              .isBefore(
				              expiresAt.minusSeconds(
						              EXPIRY_MARGIN_SECONDS
				              )
		              );
	}

	/**
	 * Refreshes under a lock, so concurrent callers do not each spend the refresh token.
	 * <p>
	 * A failure starts a cooldown: the usual cause is an expired client registration, which no
	 * amount of retrying fixes and which the operator has to resolve in the IDE.
	 */
	private void refresh() throws Exception {
		refreshLock.lock();
		try {
			if (isTokenFresh()) {
				return;
			}
			if (System.currentTimeMillis() - lastRefreshFailure <
			    REFRESH_COOLDOWN_MS) {
				throw new IllegalStateException(
						"Token refresh on cooldown (last failure <60s ago). " +
						"Most likely client registration expired — re-open Kiro IDE " +
						"to re-authorize, then restart gateway."
				);
			}
			log.info(LogTag.KIRO + "Refreshing token via {}", authType);
			apply(exchangeRefreshToken());
			saveToStore();
		} catch (Exception e) {
			lastRefreshFailure = System.currentTimeMillis();
			log.warn(LogTag.KIRO + "Refresh failed: {}", e.getMessage());
			throw e;
		} finally {
			refreshLock.unlock();
		}
	}

	private RefreshResult exchangeRefreshToken() throws Exception {
		return authType == KiroCredentials.AuthType.KIRO_DESKTOP
				? DesktopTokenRefresher.refresh(
				refreshToken,
				config.region(),
				proxyChain.currentClient()
		)
				: SsoOidcTokenRefresher.refresh(
				refreshToken,
				clientId,
				clientSecret,
				scopes,
				config.region(),
				proxyChain.currentClient()
		);
	}

	/**
	 * Keeps the existing refresh token and expiry when the upstream does not rotate them.
	 */
	private void apply(RefreshResult result) {
		this.accessToken = result.accessToken();
		if (result.refreshToken() != null) {
			this.refreshToken = result.refreshToken();
		}
		if (result.expiresAt() != null) {
			this.expiresAt = result.expiresAt();
		}
	}

	private void apply(KiroCredentials credentials) {
		if (credentials.profileArn() != null) {
			this.profileArn = credentials.profileArn();
		}
		this.clientId = credentials.clientId();
		this.clientSecret = credentials.clientSecret();
		this.accessToken = credentials.accessToken();
		this.refreshToken = credentials.refreshToken();
		this.expiresAt = credentials.expiresAt();
		this.scopes = credentials.scopes();
		this.authType = credentials.authType();
	}

	private void resolveProfileArn() {
		String arn = KiroProfileArn.fetch(accessToken, true, proxyChain);
		if (arn != null) {
			this.profileArn = arn;
			log.info(LogTag.KIRO + "Resolved profileArn: {}", arn);
		}
	}

	private void loadAccountName() {
		credentialStore.load()
		               .stream()
		               .filter(account -> profileArn != null &&
		                                  profileArn.equals(account.profileArn())
		               )
		               .findFirst()
		               .map(StoredAccount::name)
		               .ifPresent(name -> this.accountName = name);
	}

	private void saveToStore() {
		credentialStore.upsert(
				StoredAccount.builder()
				             .profileArn(profileArn)
				             .authType(authType.name())
				             .clientId(clientId)
				             .clientSecret(clientSecret)
				             .accessToken(accessToken)
				             .refreshToken(refreshToken)
				             .expiresAt(expiresAt)
				             .scopes(scopes)
				             .region(config.region())
				             .apiRegion(config.apiRegion())
				             .build()
		);
	}
}
