package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.provider.OAuthProviderAuthManager;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.instants.Xai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@RequiredArgsConstructor
@Component
public class XaiAuthManager implements OAuthProviderAuthManager {

	private final CredentialStore credentialStore;
	private final ReentrantLock refreshLock = new ReentrantLock();

	private String accessToken;
	private String refreshToken;
	private Instant expiresAt;
	private String email;

	@PostConstruct
	public void init() {
		Optional<StoredAccount> account = credentialStore.findByProvider(Provider.XAI.name());
		account.ifPresent(this::applyCredentials);
		if (account.isPresent()) {
			log.info("[xAI] Loaded credentials, email={}", email);
		}
	}

	@Override
	public String getProviderName() {
		return Provider.XAI.name();
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
			throw new IllegalStateException("xAI provider not connected");
		}
		if (expiresAt != null && Instant.now().isAfter(expiresAt.minusSeconds(300))) {
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

	public List<Map<String, Object>> listModels(StoredAccount account) throws IOException {
		if (account.accessToken() == null) {
			return List.of();
		}
		return XaiHttpClient.listModels(account.accessToken());
	}

	void refresh() {
		refreshLock.lock();
		try {
			if (expiresAt != null && Instant.now().isBefore(expiresAt.minusSeconds(300))) {
				return;
			}
			log.info("[xAI] Refreshing token");
			XaiTokenResponse response = XaiTokenRefresher.refresh(refreshToken);
			this.accessToken = response.accessToken();
			if (response.refreshToken() != null) {
				this.refreshToken = response.refreshToken();
			}
			this.expiresAt = Instant.now().plusSeconds(response.expiresIn());
			persistToStore();
			log.info("[xAI] Token refreshed, expires at {}", expiresAt);
		} catch (Exception e) {
			log.error("[xAI] Token refresh failed: {}", e.getMessage());
			disconnect();
			throw new RuntimeException("xAI token refresh failed", e);
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
		                                     .provider(Provider.XAI.name())
		                                     .clientId(Xai.CLIENT_ID)
		                                     .accessToken(accessToken)
		                                     .refreshToken(refreshToken)
		                                     .expiresAt(expiresAt)
		                                     .build();
		credentialStore.upsert(account);
	}
}
