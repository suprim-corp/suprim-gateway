package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.OAuthProviderAuthManager;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.ProxyChain;

import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@RequiredArgsConstructor
@Component
public class CodexAuthManager implements OAuthProviderAuthManager {

	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;
	private final ReentrantLock refreshLock = new ReentrantLock();
	private final ConcurrentHashMap<String, TokenState> tokenCache = new ConcurrentHashMap<>();

	private String accessToken;
	private String refreshToken;
	private Instant expiresAt;
	private String email;

	@PostConstruct
	public void init() {
		Optional<StoredAccount> account = credentialStore.findByProvider(
				Provider.CODEX.name()
		);
		account.ifPresent(this::applyCredentials);
	}

	@Override
	public String getProviderName() {
		return Provider.CODEX.name();
	}

	@Override
	public String getDisplayName() {
		return email;
	}

	public boolean isConnected() {
		return accessToken != null && refreshToken != null;
	}

	public String getAccessToken() {
		if (!isConnected()) {
			throw new IllegalStateException("Codex provider not connected");
		}

		if (expiresAt == null) {
			return accessToken;
		}

		if (Instant.now().isAfter(expiresAt.minusSeconds(300))) {
			refresh();
		}
		return accessToken;
	}

	public void saveCredentials(
			String accessToken,
			String refreshToken,
			Instant expiresAt,
			String email
	) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.expiresAt = expiresAt;
		this.email = email;
		persistToStore();
	}

	public void disconnect() {
		this.accessToken = null;
		this.refreshToken = null;
		this.expiresAt = null;
		this.email = null;
		persistToStore();
	}

	public Map<String, Object> getUsageLimits(StoredAccount account) {
		return CodexHttpClient.fetchUsage(getAccessToken(account), proxyChain);
	}

	public List<Map<String, Object>> listModels(StoredAccount account) throws IOException {
		if (account.accessToken() == null) {
			return List.of();
		}
		return CodexHttpClient.listModels(account.accessToken(), proxyChain);
	}

	public String getAccessToken(StoredAccount account) {
		String key = Optional.ofNullable(account.name())
		                     .orElse(account.clientId());
		TokenState state = tokenCache.computeIfAbsent(
				key, k -> TokenState.builder()
				                    .accessToken(account.accessToken())
				                    .refreshToken(account.refreshToken())
				                    .expiresAt(account.expiresAt())
				                    .build()
		);
		if (state.isExpired()) {
			state = refreshForAccount(account, state);
			tokenCache.put(key, state);
		}
		return state.accessToken();
	}

	void refresh() {
		refreshLock.lock();
		try {
			if (expiresAt != null &&
			    Instant.now().isBefore(expiresAt.minusSeconds(300))) {
				return;
			}
			log.info("[Codex] Refreshing token");
			CodexTokenResponse response = CodexTokenRefresher.refresh(
					refreshToken);
			this.accessToken = response.accessToken();
			if (response.refreshToken() != null) {
				this.refreshToken = response.refreshToken();
			}
			this.expiresAt = Instant.now().plusSeconds(response.expiresIn());
			persistToStore();
			log.info("[Codex] Token refreshed, expires at {}", expiresAt);
		} catch (Exception e) {
			log.error("[Codex] Token refresh failed: {}", e.getMessage());
			disconnect();
			throw new RuntimeException("Codex token refresh failed", e);
		} finally {
			refreshLock.unlock();
		}
	}

	private void applyCredentials(StoredAccount account) {
		this.accessToken = account.accessToken();
		this.refreshToken = account.refreshToken();
		this.expiresAt = account.expiresAt();
		this.email = account.name();
	}

	private void persistToStore() {
		StoredAccount account = StoredAccount.builder()
		                                     .name(email)
		                                     .provider(Provider.CODEX.name())
		                                     .clientId(Codex.CLIENT_ID)
		                                     .accessToken(accessToken)
		                                     .refreshToken(refreshToken)
		                                     .expiresAt(expiresAt)
		                                     .build();
		credentialStore.upsert(account);
	}

	private TokenState refreshForAccount(
			StoredAccount account,
			TokenState current
	) {
		String rfshToken = current.refreshToken();
		if (rfshToken == null) {
			throw new RuntimeException(
					"No refresh token for Codex account: " + account.name()
			);
		}
		try {
			log.info(
					"[Codex] Refreshing token for account: {}",
					account.name()
			);
			CodexTokenResponse response = CodexTokenRefresher.refresh(rfshToken);
			TokenState newState =
					TokenState.builder()
					          .accessToken(response.accessToken())
					          .refreshToken(
							          Optional.ofNullable(response.refreshToken())
							                  .orElse(rfshToken)
					          )
					          .expiresAt(
							          Instant.now()
							                 .plusSeconds(response.expiresIn())
					          )
					          .build();
			credentialStore.upsert(account.withTokens(
					newState.accessToken(),
					newState.refreshToken(),
					newState.expiresAt()
			));
			return newState;
		} catch (Exception e) {
			log.error(
					"[Codex] Token refresh failed for {}: {}",
					account.name(),
					e.getMessage()
			);
			throw new RuntimeException(
					"Codex token refresh failed for " + account.name(),
					e
			);
		}
	}

	@Builder
	private record TokenState(
			String accessToken, String refreshToken, Instant expiresAt
	) {
		boolean isExpired() {
			return expiresAt == null ||
			       Instant.now().isAfter(expiresAt.minusSeconds(300));
		}
	}
}
