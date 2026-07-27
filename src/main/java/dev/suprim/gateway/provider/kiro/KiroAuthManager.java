package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.ProviderAuthManager;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.KiroAccountRefresher.TokenState;
import dev.suprim.gateway.proxy.ProxyChain;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kiro credentials and the control-plane calls that need them.
 * <p>
 * Two account models coexist here. {@link KiroPrimaryAccount} holds the single set of credentials
 * the UI reports as connected, while {@link #accountTokenCache} holds a token per stored account so
 * requests can rotate between them. Per-account methods take a {@link StoredAccount}; the no-arg
 * ones act on the primary account.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KiroAuthManager implements ProviderAuthManager {

	private final AppConfig config;
	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;
	private final ConcurrentHashMap<String, TokenState> accountTokenCache =
			new ConcurrentHashMap<>();

	private KiroPrimaryAccount primaryAccount;

	@PostConstruct
	void init() {
		primaryAccount = new KiroPrimaryAccount(
				config,
				credentialStore,
				proxyChain
		);
		primaryAccount.load();
	}

	public String getAccessToken() throws Exception {
		return primaryAccount.accessToken();
	}

	/**
	 * A usable token for one stored account, refreshing when the cached one has expired. An API
	 * key is returned as-is, since it does not expire.
	 *
	 * @throws IllegalStateException when the account's refresh token is no longer accepted
	 */
	public String getAccessToken(StoredAccount account) throws Exception {
		if (isApiKey(account)) {
			return account.accessToken();
		}
		String key = accountKey(account);
		TokenState state = accountTokenCache.computeIfAbsent(
				key,
				ignored -> TokenState.of(account)
		);
		if (state.isExpired()) {
			state = TokenState.of(refreshOrThrow(account));
			accountTokenCache.put(key, state);
		}
		return state.accessToken();
	}

	public void forceRefresh() throws Exception {
		primaryAccount.forceRefresh();
	}

	/**
	 * Refreshes one account's token regardless of expiry, persisting the result.
	 */
	public String forceRefresh(StoredAccount account) {
		if (isApiKey(account)) {
			return account.accessToken();
		}
		StoredAccount refreshed = refreshOrThrow(account);
		credentialStore.upsert(refreshed);
		accountTokenCache.put(accountKey(account), TokenState.of(refreshed));
		return refreshed.accessToken();
	}

	public boolean isApiKeyAuth() {
		return primaryAccount.isApiKeyAuth();
	}

	public String getProfileArn() {
		return primaryAccount.getProfileArn();
	}

	/**
	 * Imports an account, adopting it as the primary one when it is the only account stored or
	 * when it replaces the profile already held.
	 */
	public ImportResult importAccount(ImportRequest request) throws Exception {
		ImportResult result = KiroAccountImporter.execute(
				request,
				credentialStore,
				proxyChain.currentClient()
		);
		String currentArn = primaryAccount.getProfileArn();
		boolean isOnlyAccount = credentialStore.load().size() == 1;
		if (isOnlyAccount ||
		    (currentArn != null && currentArn.equals(result.profileArn()))) {
			primaryAccount.adopt(result.account());
		}
		return result;
	}

	@Cacheable(value = "kiroModels", key = "T(dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability).accountKey(#account)")
	public List<Map<String, Object>> listModels(StoredAccount account)
			throws Exception {
		return KiroModelsClient.fetch(
				account,
				config.apiRegion(),
				config.disabledModelsSet(),
				proxyChain,
				() -> refreshAndStore(account)
		);
	}

	/**
	 * Usage limits for one account, or {@code {"error": "..."}} when the lookup fails.
	 */
	public Map<String, Object> getUsageLimits(StoredAccount account) {
		try {
			return KiroUsageLimits.fetch(
					account,
					getAccessToken(account),
					KiroAccountRegion.resolve(account, config.apiRegion()),
					proxyChain,
					() -> refreshAndStore(account)
			);
		} catch (Exception e) {
			String message = Optional.ofNullable(e.getMessage())
			                         .orElse("Unknown error");
			log.warn("[Usage] getUsageLimits failed: {}", message);
			return Map.of("error", message);
		}
	}

	public String fetchEmailForApiKey(String apiKey) {
		return KiroUsageLimits.fetchEmail(apiKey, proxyChain);
	}

	public String resolveProfileArnForApiKey(String apiKey) {
		return KiroProfileArn.fetch(apiKey, true, proxyChain);
	}

	String getRegion() {
		return config.region();
	}

	String getApiRegion() {
		return config.apiRegion();
	}

	@Override
	public String getProviderName() {
		return Provider.KIRO.name();
	}

	@Override
	public String getDisplayName() {
		return primaryAccount.displayName();
	}

	@Override
	public boolean isConnected() {
		return primaryAccount.isConnected();
	}

	@Override
	public void disconnect() {
		primaryAccount.disconnect();
	}

	/**
	 * A fresh access token for {@code account}, persisted so the next request starts from it.
	 * Null when the refresh failed, which the caller surfaces as the original upstream failure
	 * rather than retrying.
	 */
	private String refreshAndStore(StoredAccount account) {
		StoredAccount refreshed = KiroAccountRefresher.refresh(
				account,
				proxyChain.currentClient()
		);
		if (refreshed == null) {
			return null;
		}
		credentialStore.upsert(refreshed);
		return refreshed.accessToken();
	}

	private StoredAccount refreshOrThrow(StoredAccount account) {
		StoredAccount refreshed = KiroAccountRefresher.refresh(
				account,
				proxyChain.currentClient()
		);
		if (refreshed == null) {
			throw new IllegalStateException(
					"Kiro token refresh failed for " + account.name()
			);
		}
		return refreshed;
	}

	private static boolean isApiKey(StoredAccount account) {
		return "API_KEY".equalsIgnoreCase(account.authType());
	}

	private static String accountKey(StoredAccount account) {
		return KiroAccountModelAvailability.accountKey(account);
	}
}
