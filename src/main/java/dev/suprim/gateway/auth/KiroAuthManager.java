package dev.suprim.gateway.auth;

import dev.suprim.gateway.auth.reader.CredentialStoreReader;
import dev.suprim.gateway.auth.reader.KiroSourceReader;
import dev.suprim.gateway.auth.refresher.DesktopTokenRefresher;
import dev.suprim.gateway.auth.refresher.RefreshResult;
import dev.suprim.gateway.auth.refresher.SsoOidcTokenRefresher;
import dev.suprim.gateway.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class KiroAuthManager {

	private static final Logger log = LoggerFactory.getLogger(KiroAuthManager.class);
	private static final long REFRESH_COOLDOWN_MS = 60_000;

	private final AppConfig config;
	private final KiroCredentialStore credentialStore;
	private final HttpClient httpClient = HttpClient.newBuilder()
	                                                .connectTimeout(
			                                                Duration.ofSeconds(
					                                                10
			                                                )
	                                                )
	                                                .build();
	private final ReentrantLock refreshLock = new ReentrantLock();

	private String accessToken;
	private String refreshToken;
	private Instant expiresAt;
	@Getter
	private String profileArn;
	private String clientId;
	private String clientSecret;
	private String[] scopes;
	private KiroCredentials.AuthType authType = KiroCredentials.AuthType.KIRO_DESKTOP;
	private long lastRefreshFailure = 0;
	private String credSourceType;
	private String credSourcePath;

	KiroAuthManager(AppConfig config, KiroCredentialStore credentialStore) {
		this.config = config;
		this.credentialStore = credentialStore;
	}

	@PostConstruct
	void init() {
		this.profileArn = config.profileArn();

		// credential store có sẵn → dùng luôn, không cần đọc Kiro DB
		Optional<KiroCredentials> fromStore = CredentialStoreReader.read(
				credentialStore
		);

		if (fromStore.isPresent()) {
			applyCredentials(fromStore.get());
			log.info(
					"[Auth] Initialized from credential store: type={}, region={}, apiRegion={}",
					authType,
					config.region(),
					config.apiRegion()
			);
			return;
		}

		// fallback: đọc từ Kiro DB / JSON config, rồi bootstrap vào store
		if (config.cliDbFile() != null && !config.cliDbFile().isBlank()) {
			credSourceType = "sqlite";
			credSourcePath = config.cliDbFile();
		} else if (config.credsFile() != null &&
		           !config.credsFile().isBlank()) {
			credSourceType = "json";
			credSourcePath = config.credsFile();
		}
		KiroSourceReader.read(config).ifPresent(this::applyCredentials);
		bootstrapStore();
		log.info(
				"[Auth] Initialized: type={}, region={}, apiRegion={}",
				authType,
				config.region(),
				config.apiRegion()
		);
	}

	public String getApiHost() {
		return "https://runtime." + config.apiRegion() + ".kiro.dev";
	}

	public String getQHost() {
		return "https://q." + config.apiRegion() + ".amazonaws.com";
	}

	public String getAccessToken() throws Exception {
		if (accessToken != null && expiresAt != null && Instant.now().isBefore(
				expiresAt.minusSeconds(600))) {
			return accessToken;
		}
		refresh();
		return accessToken;
	}

	public void forceRefresh() throws Exception {
		refresh();
	}

	String getRegion() {
		return config.region();
	}

	String getApiRegion() {
		return config.apiRegion();
	}

	@Scheduled(fixedDelay = 2_700_000)
		// 45 phút
	void scheduledRefresh() {
		if (refreshToken == null) return;
		try {
			log.info("[Auth] Scheduled token refresh");
			refresh();
			log.info("[Auth] Scheduled refresh OK, expires at {}", expiresAt);
		} catch (Exception e) {
			log.warn("[Auth] Scheduled refresh failed: {}", e.getMessage());
		}
	}

	private void refresh() throws Exception {
		refreshLock.lock();
		try {
			if (accessToken != null && expiresAt != null &&
			    Instant.now().isBefore(expiresAt.minusSeconds(600))) {
				return;
			}
			if (System.currentTimeMillis() - lastRefreshFailure <
			    REFRESH_COOLDOWN_MS) {
				throw new RuntimeException(
						"Token refresh on cooldown (last failure <60s ago). " +
						"Most likely client registration expired — re-open Kiro IDE to re-authorize, then restart gateway.");
			}

			log.info("[Auth] Refreshing token via {}", authType);
			try {
				doRefresh();
			} catch (Exception e) {
				log.warn(
						"[Auth] Refresh failed, reloading from Kiro DB: {}",
						e.getMessage()
				);
				Optional<KiroCredentials> reloaded = KiroSourceReader.read(
						config);
				if (reloaded.isEmpty()) {
					throw e;
				}
				applyCredentials(reloaded.get());
				doRefresh();
			}
			saveToStore();
		} catch (Exception e) {
			lastRefreshFailure = System.currentTimeMillis();
			throw e;
		} finally {
			refreshLock.unlock();
		}
	}

	private void doRefresh() throws Exception {
		RefreshResult result;
		if (authType == KiroCredentials.AuthType.KIRO_DESKTOP) {
			result = DesktopTokenRefresher.refresh(
					refreshToken,
					config.region(),
					httpClient
			);
		} else {
			result = SsoOidcTokenRefresher.refresh(
					refreshToken,
					clientId,
					clientSecret,
					scopes,
					config.region(),
					httpClient
			);
		}
		this.accessToken = result.accessToken();
		if (result.refreshToken() != null) this.refreshToken = result.refreshToken();
		if (result.expiresAt() != null) this.expiresAt = result.expiresAt();
	}

	private void applyCredentials(KiroCredentials creds) {
		if (creds.profileArn() != null) this.profileArn = creds.profileArn();
		this.clientId = creds.clientId();
		this.clientSecret = creds.clientSecret();
		this.accessToken = creds.accessToken();
		this.refreshToken = creds.refreshToken();
		this.expiresAt = creds.expiresAt();
		this.scopes = creds.scopes();
		this.authType = creds.authType();
	}

	private void bootstrapStore() {
		if (refreshToken == null && accessToken == null) return;
		try {
			refresh();
		} catch (Exception e) {
			log.warn(
					"[Auth] Bootstrap refresh failed, not saving to store: {}",
					e.getMessage()
			);
			return;
		}
		saveToStore();
		log.info(
				"[Auth] Bootstrapped credential store from {}",
				credSourceType
		);
	}

	private void saveToStore() {
		StoredAccount account =
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
				             .build();
		credentialStore.save(List.of(account));
	}
}
