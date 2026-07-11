package dev.suprim.gateway.antigravity;

import dev.suprim.gateway.instants.Antigravity;

import dev.suprim.gateway.auth.KiroCredentialStore;
import dev.suprim.gateway.auth.StoredAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@RequiredArgsConstructor
@Component
public class AntigravityAuthManager {

	private final KiroCredentialStore credentialStore;
	private final ReentrantLock refreshLock = new ReentrantLock();

	private String accessToken;
	private String refreshToken;
	private Instant expiresAt;
	private String projectId;

	@PostConstruct
	public void init() {
		Optional<StoredAccount> account = credentialStore.findByProvider(
				Antigravity.PROVIDER
		);
		account.ifPresent(this::applyCredentials);
		if (account.isPresent()) {
			log.info(
					"[Antigravity] Loaded credentials, projectId={}",
					projectId
			);
		}
	}

	public boolean isConnected() {
		return accessToken != null && refreshToken != null;
	}

	public String getAccessToken() {
		if (!isConnected()) {
			throw new IllegalStateException("Antigravity provider not connected");
		}
		if (expiresAt != null && Instant.now().isAfter(expiresAt.minusSeconds(
				300))) {
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
			String projectId
	) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.expiresAt = expiresAt;
		this.projectId = projectId;
		persistToStore();
	}

	public void disconnect() {
		this.accessToken = null;
		this.refreshToken = null;
		this.expiresAt = null;
		this.projectId = null;
	}

	public List<Map<String, Object>> listModels() throws IOException {
		if (!isConnected()) {
			return List.of();
		}
		return AntigravityHttpClient.listModels(getAccessToken(), projectId);
	}

	void refresh() {
		refreshLock.lock();
		try {
			if (expiresAt != null &&
			    Instant.now().isBefore(expiresAt.minusSeconds(300))) {
				return;
			}
			log.info("[Antigravity] Refreshing token");
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
			log.info("[Antigravity] Token refreshed, expires at {}", expiresAt);
		} catch (Exception e) {
			log.error("[Antigravity] Token refresh failed: {}", e.getMessage());
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
	}

	private void persistToStore() {
		StoredAccount account = StoredAccount.builder()
		                                     .name("Antigravity")
		                                     .provider(Antigravity.PROVIDER)
		                                     .clientId(Antigravity.CLIENT_ID)
		                                     .clientSecret(Antigravity.CLIENT_SECRET)
		                                     .accessToken(accessToken)
		                                     .refreshToken(refreshToken)
		                                     .expiresAt(expiresAt)
		                                     .projectId(projectId)
		                                     .build();
		credentialStore.upsert(account);
	}
}
