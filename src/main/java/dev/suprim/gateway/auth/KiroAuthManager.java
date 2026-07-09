package dev.suprim.gateway.auth;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
		if (credentialStore.exists()) {
			List<StoredAccount> accounts = credentialStore.load();
			if (!accounts.isEmpty()) {
				loadFromStore(accounts.getFirst());
				detectAuthType();
				log.info(
						"[Auth] Initialized from credential store: type={}, region={}, apiRegion={}",
						authType,
						config.region(),
						config.apiRegion()
				);
				return;
			}
		}

		// fallback: đọc từ Kiro DB / JSON config, rồi bootstrap vào store
		if (config.cliDbFile() != null && !config.cliDbFile().isBlank()) {
			credSourceType = "sqlite";
			credSourcePath = config.cliDbFile();
			loadFromSqlite(resolvePath(credSourcePath));
		} else if (config.credsFile() != null &&
		           !config.credsFile().isBlank()) {
			credSourceType = "json";
			credSourcePath = config.credsFile();
			loadFromJson(resolvePath(credSourcePath));
		} else if (config.refreshToken() != null &&
		           !config.refreshToken().isBlank()) {
			this.refreshToken = config.refreshToken();
		}
		detectAuthType();
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
			if (authType == KiroCredentials.AuthType.KIRO_DESKTOP) {
				refreshDesktop();
			} else {
				refreshSsoOidc();
			}
			saveToStore();
		} catch (Exception e) {
			lastRefreshFailure = System.currentTimeMillis();
			throw e;
		} finally {
			refreshLock.unlock();
		}
	}

	private void refreshDesktop() throws Exception {
		String url = "https://prod." + config.region() +
		             ".auth.desktop.kiro.dev/refreshToken";
		String body = mapper.writeValueAsString(Map.of(
				"refreshToken",
				refreshToken
		));
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(HttpRequest.BodyPublishers.ofString(
				                                 body))
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
					));
		}
		JsonNode json = mapper.readTree(response.body());
		this.accessToken = json.get("accessToken").asString();
		if (json.has("refreshToken")) this.refreshToken = json.get(
				"refreshToken").asString();
		if (json.has("expiresAt")) this.expiresAt = Instant.parse(json.get(
				"expiresAt").asString());
	}

	private void refreshSsoOidc() throws Exception {
		String url = "https://oidc." + config.region() + ".amazonaws.com/token";
		ObjectNode payload = mapper.createObjectNode();
		payload.put("grant_type", "refresh_token");
		payload.put("client_id", clientId);
		payload.put("client_secret", clientSecret);
		payload.put("refresh_token", refreshToken);
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
		                                 .POST(HttpRequest.BodyPublishers.ofString(
				                                 mapper.writeValueAsString(
						                                 payload)))
		                                 .build();
		HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			String responseBody = response.body();
			String detail = responseBody.substring(
					0, Math.min(300, responseBody.length()));
			String msg = responseBody.contains("invalid_client")
					?
					"SSO OIDC client registration expired (~90 days). Re-open Kiro IDE to re-authorize, then restart gateway. Raw: " +
					detail
					: "SSO OIDC refresh failed (" + response.statusCode() +
					  "): " + detail;
			throw new RuntimeException(msg);
		}
		JsonNode json = mapper.readTree(response.body());
		this.accessToken = textOrNull(json, "access_token") != null
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

	private void loadFromStore(StoredAccount account) {
		this.profileArn = account.profileArn() !=
		                  null ? account.profileArn() : this.profileArn;
		this.clientId = account.clientId();
		this.clientSecret = account.clientSecret();
		this.accessToken = account.accessToken();
		this.refreshToken = account.refreshToken();
		this.expiresAt = account.expiresAt();
		this.scopes = account.scopes();
	}

	private void bootstrapStore() {
		if (refreshToken == null && accessToken == null) return;
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

	private void loadFromJson(Path path) {
		try {
			if (!Files.exists(path)) {
				log.warn("[Auth] JSON creds not found: {}", path);
				return;
			}
			JsonNode json = mapper.readTree(Files.readString(path));
			this.accessToken = textOrNull(json, "accessToken");
			this.refreshToken = textOrNull(json, "refreshToken");
			if (json.has("expiresAt")) {
				this.expiresAt = Instant.parse(
						json.get("expiresAt").asString()
				);
			}
			if (json.has("profileArn") && this.profileArn == null) {
				this.profileArn = json.get("profileArn").asString();
			}

			this.clientId = textOrNull(json, "clientId");
			this.clientSecret = textOrNull(json, "clientSecret");
		} catch (Exception e) {
			log.error("[Auth] Failed to load JSON creds: {}", e.getMessage());
		}
	}

	private void loadFromSqlite(Path path) {
		try {
			if (!Files.exists(path)) {
				log.warn("[Auth] SQLite DB not found: {}", path);
				return;
			}
			String jdbcUrl = "jdbc:sqlite:" + path;
			try (
					Connection conn = DriverManager.getConnection(jdbcUrl);
					Statement stmt = conn.createStatement()
			) {
				stmt.execute("PRAGMA wal_checkpoint(PASSIVE)");

				try (ResultSet rs = stmt.executeQuery(
						"SELECT value FROM auth_kv WHERE key = 'kirocli:odic:device-registration'")) {
					if (rs.next()) {
						JsonNode reg = mapper.readTree(rs.getString("value"));
						this.clientId = textOrNull(reg, "client_id");
						this.clientSecret = textOrNull(reg, "client_secret");
						if (reg.has("scopes") && reg.get("scopes").isArray()) {
							this.scopes = new String[reg.get("scopes").size()];
							for (int i = 0; i < reg.get("scopes").size(); i++) {
								this.scopes[i] = reg.get("scopes")
								                    .get(i)
								                    .asString();
							}
						}
					}
				}

				String[] tokenKeys = {"kirocli:social:token", "kirocli:odic:token", "codewhisperer:odic:token"};
				for (String key : tokenKeys) {
					try (ResultSet rs = stmt.executeQuery(
							"SELECT value FROM auth_kv WHERE key = '" + key +
							"'")) {
						if (rs.next()) {
							JsonNode json = mapper.readTree(rs.getString("value"));
							this.accessToken = textOrNull(json, "access_token");
							if (this.accessToken == null) {
								this.accessToken = textOrNull(
										json,
										"accessToken"
								);
							}

							this.refreshToken = textOrNull(
									json,
									"refresh_token"
							);

							if (this.refreshToken == null) {
								this.refreshToken = textOrNull(
										json,
										"refreshToken"
								);
							}

							String exp = textOrNull(json, "expires_at");
							if (exp == null) exp = textOrNull(
									json,
									"expiresAt"
							);
							if (exp != null)
								this.expiresAt = Instant.parse(exp);
							if (this.clientId == null) {
								this.clientId = textOrNull(json, "client_id");
							}

							if (this.clientSecret == null) {
								this.clientSecret = textOrNull(
										json,
										"client_secret"
								);
							}

							if (this.accessToken != null ||
							    this.refreshToken != null) break;
						}
					}
				}
			}
		} catch (Exception e) {
			log.error("[Auth] Failed to load SQLite creds: {}", e.getMessage());
		}
	}

	private void detectAuthType() {
		if (clientId != null && clientSecret != null) {
			this.authType = KiroCredentials.AuthType.AWS_SSO_OIDC;
		} else {
			this.authType = KiroCredentials.AuthType.KIRO_DESKTOP;
		}
	}

	private static String textOrNull(JsonNode node, String field) {
		return node.has(field) && !node.get(field).isNull() ? node.get(field)
		                                                          .asString() : null;
	}

	private static Path resolvePath(String path) {
		if (path.startsWith("~")) {
			return Path.of(System.getProperty("user.home"))
			           .resolve(path.substring(2));
		}
		return Path.of(path);
	}
}
