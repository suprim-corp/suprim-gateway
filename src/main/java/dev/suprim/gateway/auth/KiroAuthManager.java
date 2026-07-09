package dev.suprim.gateway.auth;

import dev.suprim.gateway.auth.reader.CredentialStoreReader;
import dev.suprim.gateway.auth.reader.KiroSourceReader;
import dev.suprim.gateway.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class KiroAuthManager {

	private static final Logger log = LoggerFactory.getLogger(KiroAuthManager.class);
	private static final long REFRESH_COOLDOWN_MS = 60_000;

	private final AppConfig config;
	private final KiroCredentialStore credentialStore;
	private final ObjectMapper mapper = new ObjectMapper();
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
		if (authType == KiroCredentials.AuthType.KIRO_DESKTOP) {
			refreshDesktop();
		} else {
			refreshSsoOidc();
		}
	}

	private void refreshDesktop() throws Exception {
		String url = "https://prod." + config.region() +
		             ".auth.desktop.kiro.dev/refreshToken";
		String body = mapper.writeValueAsString(
				Map.of(
						"refreshToken",
						refreshToken
				)
		);
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(
				                                 HttpRequest.BodyPublishers.ofString(
						                                 body
				                                 )
		                                 )
		                                 .build();
		HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			String responseBody = response.body();
			throw new RuntimeException(
					"Desktop refresh failed (" + response.statusCode() + "): " +
					responseBody.substring(
							0,
							Math.min(300, responseBody.length())
					)
			);
		}
		JsonNode json = mapper.readTree(response.body());
		this.accessToken = json.get("accessToken").asString();
		if (json.has("refreshToken")) {
			this.refreshToken = json.get("refreshToken").asString();
		}
		if (json.has("expiresAt")) {
			this.expiresAt = Instant.parse(json.get("expiresAt").asString());
		}
	}

	// AWS SSO OIDC accepts camelCase keys (ref: Kiro-Go/auth/oidc.go)
	private void refreshSsoOidc() throws Exception {
		String url = "https://oidc." + config.region() + ".amazonaws.com/token";
		ObjectNode payload = mapper.createObjectNode();
		payload.put("grantType", "refresh_token");
		payload.put("clientId", clientId);
		payload.put("clientSecret", clientSecret);
		payload.put("refreshToken", refreshToken);
		if (scopes != null && scopes.length > 0) {
			ArrayNode arr = payload.putArray("scope");
			for (String s : scopes) arr.add(s);
		}
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(
				                                 HttpRequest.BodyPublishers.ofString(
						                                 mapper.writeValueAsString(
								                                 payload
						                                 )
				                                 )
		                                 )
		                                 .build();
		HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			String responseBody = response.body();
			String detail = responseBody.substring(
					0, Math.min(300, responseBody.length())
			);
			String msg = responseBody.contains("invalid_client")
					?
					"SSO OIDC client registration expired (~90 days). Re-open Kiro IDE to re-authorize, then restart gateway. Raw: " +
					detail
					: "SSO OIDC refresh failed (" + response.statusCode() +
					  "): " + detail;
			throw new RuntimeException(msg);
		}
		JsonNode json = mapper.readTree(response.body());
		this.accessToken =
				json.has("access_token") && !json.get("access_token").isNull()
						? json.get("access_token").asString()
						: json.get("accessToken").asString();
		if (json.has("refresh_token")) {
			this.refreshToken = json.get("refresh_token").asString();
		} else if (json.has("refreshToken")) {
			this.refreshToken = json.get("refreshToken").asString();
		}
		int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt()
				: (json.has("expiresIn") ? json.get("expiresIn")
				                               .asInt() : 3600);
		this.expiresAt = Instant.now().plusSeconds(expiresIn);
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
