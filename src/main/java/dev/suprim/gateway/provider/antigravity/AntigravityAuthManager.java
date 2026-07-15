package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
import dev.suprim.gateway.logging.LogTag;

import dev.suprim.gateway.provider.OAuthProviderAuthManager;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

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
public class AntigravityAuthManager implements OAuthProviderAuthManager {

	private final CredentialStore credentialStore;
	private final ReentrantLock refreshLock = new ReentrantLock();
	private final ConcurrentHashMap<String, TokenState> tokenCache = new ConcurrentHashMap<>();

	private String accessToken;
	private String refreshToken;
	private Instant expiresAt;
	private String projectId;
	private String email;

	@PostConstruct
	public void init() {
		Optional<StoredAccount> account = credentialStore.findByProvider(
				Provider.ANTIGRAVITY.name()
		);
		account.ifPresent(this::applyCredentials);
	}

	@Override
	public String getProviderName() {
		return Provider.ANTIGRAVITY.name();
	}

	@Override
	public String getDisplayName() {
		return email;
	}

	@Override
	public boolean isConnected() {
		return accessToken != null && refreshToken != null;
	}

	public String getAccessToken() {
		if (!isConnected()) {
			throw new IllegalStateException("Antigravity provider not connected");
		}

		if (expiresAt == null) {
			return accessToken;
		}

		if (Instant.now().isAfter(expiresAt.minusSeconds(300))) {
			refresh();
		}

		return accessToken;
	}

	public String getProjectId() {
		if (!isConnected()) {
			throw new IllegalStateException("Antigravity provider not connected");
		}
		return projectId;
	}

	public void saveCredentials(
			String accessToken,
			String refreshToken,
			Instant expiresAt,
			String projectId,
			String email
	) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.expiresAt = expiresAt;
		this.projectId = projectId;
		this.email = email;
		persistToStore();
	}

	public void disconnect() {
		this.accessToken = null;
		this.refreshToken = null;
		this.expiresAt = null;
		this.projectId = null;
	}

	@Cacheable("antigravityModels")
	public List<Map<String, Object>> listModels(StoredAccount account) throws IOException {
		if (account.accessToken() == null) {
			return List.of();
		}
		String token = getAccessToken(account);
		return AntigravityHttpClient.listModels(token, account.projectId());
	}

	public String getAccessToken(StoredAccount account) {
		String key =
				account.name() != null ? account.name() : account.clientId();
		TokenState state = tokenCache.computeIfAbsent(
				key, k -> new TokenState(
						account.accessToken(),
						account.refreshToken(),
						account.expiresAt()
				)
		);
		if (state.isExpired()) {
			state = refreshForAccount(account, state);
			tokenCache.put(key, state);
		}
		return state.accessToken();
	}

	public String getProjectId(StoredAccount account) {
		return account.projectId();
	}

	void refresh() {
		refreshLock.lock();
		try {
			if (expiresAt != null &&
			    Instant.now().isBefore(expiresAt.minusSeconds(300))) {
				return;
			}
			log.info(LogTag.ANTIGRAVITY + "Refreshing token");
			GoogleTokenResponse response = GoogleTokenRefresher.refresh(
					refreshToken,
					Antigravity.CLIENT_ID,
					Antigravity.CLIENT_SECRET
			);
			this.accessToken = response.accessToken();
			if (response.refreshToken() != null) {
				this.refreshToken = response.refreshToken();
			}
			this.expiresAt = Instant.now().plusSeconds(response.expiresIn());
			persistToStore();
			log.info(LogTag.ANTIGRAVITY + "Token refreshed, expires at {}", expiresAt);
		} catch (Exception e) {
			log.error(LogTag.ANTIGRAVITY + "Token refresh failed: {}", e.getMessage());
			throw new RuntimeException("Antigravity token refresh failed", e);
		} finally {
			refreshLock.unlock();
		}
	}

	private void applyCredentials(StoredAccount account) {
		this.accessToken = account.accessToken();
		this.refreshToken = account.refreshToken();
		this.expiresAt = account.expiresAt();
		this.projectId = account.projectId();
		this.email = account.name();
	}

	private void persistToStore() {
		StoredAccount account =
				StoredAccount.builder()
				             .name(email)
				             .provider(Provider.ANTIGRAVITY.name())
				             .clientId(Antigravity.CLIENT_ID)
				             .clientSecret(Antigravity.CLIENT_SECRET)
				             .accessToken(accessToken)
				             .refreshToken(refreshToken)
				             .expiresAt(expiresAt)
				             .projectId(projectId)
				             .build();
		credentialStore.upsert(account);
	}

	private TokenState refreshForAccount(
			StoredAccount account,
			TokenState current
	) {
		String refreshTok = current.refreshToken();
		if (refreshTok == null) {
			throw new RuntimeException(
					"No refresh token for Antigravity account: " +
					account.name());
		}
		try {
			log.info(
					LogTag.ANTIGRAVITY + "Refreshing token for account: {}",
					account.name()
			);
			GoogleTokenResponse response = GoogleTokenRefresher.refresh(
					refreshTok, Antigravity.CLIENT_ID, Antigravity.CLIENT_SECRET
			);
			TokenState newState = new TokenState(
					response.accessToken(),
					response.refreshToken() !=
					null ? response.refreshToken() : refreshTok,
					Instant.now().plusSeconds(response.expiresIn())
			);
			credentialStore.upsert(account.withTokens(
					newState.accessToken(),
					newState.refreshToken(),
					newState.expiresAt()
			));
			return newState;
		} catch (Exception e) {
			log.error(
					LogTag.ANTIGRAVITY + "Token refresh failed for {}: {}",
					account.name(),
					e.getMessage()
			);
			throw new RuntimeException(
					"Antigravity token refresh failed for " + account.name(),
					e
			);
		}
	}

	private record TokenState(
			String accessToken, String refreshToken, Instant expiresAt
	) {
		boolean isExpired() {
			return expiresAt == null ||
			       Instant.now().isAfter(expiresAt.minusSeconds(300));
		}
	}
}
