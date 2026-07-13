package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.ProviderAuthManager;

import dev.suprim.gateway.provider.kiro.reader.CredentialStoreReader;
import dev.suprim.gateway.provider.kiro.reader.KiroSourceReader;
import dev.suprim.gateway.provider.kiro.refresher.DesktopTokenRefresher;
import dev.suprim.gateway.provider.kiro.refresher.RefreshResult;
import dev.suprim.gateway.provider.kiro.refresher.SsoOidcTokenRefresher;
import dev.suprim.gateway.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.nonNull;

@Slf4j
@Component
@RequiredArgsConstructor
public class KiroAuthManager implements ProviderAuthManager {

	private static final long REFRESH_COOLDOWN_MS = 60_000;

	private final AppConfig config;
	private final CredentialStore credentialStore;
	private final HttpClient httpClient =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(10))
			          .build();
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
	private KiroCredentials.AuthType authType = KiroCredentials.AuthType.KIRO_DESKTOP;
	private long lastRefreshFailure = 0;
	private String credSourceType;
	private String credSourcePath;

	@PostConstruct
	void init() {
		this.profileArn = config.profileArn();

		// credential store có sẵn → dùng luôn, không cần đọc Kiro DB
		Optional<KiroCredentials> fromStore = CredentialStoreReader.read(
				credentialStore
		);

		if (fromStore.isPresent()) {
			applyCredentials(fromStore.get());
			loadAccountName();
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
		loadAccountName();
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

	public ImportResult importAccount(ImportRequest req) throws Exception {
		ImportResult result = KiroAccountImporter.execute(
				req, credentialStore, httpClient
		);
		List<StoredAccount> before = credentialStore.load();
		boolean shouldApply = before.size() == 1 ||
		                      (profileArn != null &&
		                       profileArn.equals(result.profileArn())
		                      );
		if (shouldApply) {
			StoredAccount acc = result.account();
			this.accessToken = acc.accessToken();
			this.refreshToken = acc.refreshToken();
			this.expiresAt = acc.expiresAt();
			this.clientId = acc.clientId();
			this.clientSecret = acc.clientSecret();
			this.authType = KiroCredentials.AuthType.valueOf(acc.authType());
			if (acc.profileArn() != null) this.profileArn = acc.profileArn();
			loadAccountName();
		}
		return result;
	}

	String getRegion() {
		return config.region();
	}

	String getApiRegion() {
		return config.apiRegion();
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
		if (result.refreshToken() != null)
			this.refreshToken = result.refreshToken();
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

	@Override
	public String getProviderName() {
		return Provider.KIRO.name();
	}

	@Override
	public String getDisplayName() {
		return accountName != null ? accountName : profileArn;
	}

	@Override
	public boolean isConnected() {
		return accessToken != null && refreshToken != null;
	}

	@Override
	public void disconnect() {
		this.accessToken = null;
		this.refreshToken = null;
		this.expiresAt = null;
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
		credentialStore.upsert(account);
	}

	private void loadAccountName() {
		credentialStore.load()
		               .stream()
		               .filter(a -> nonNull(profileArn) &&
		                            profileArn.equals(a.profileArn())
		               )
		               .findFirst()
		               .map(StoredAccount::name)
		               .ifPresent(name -> this.accountName = name);
	}

	private static final Pattern DOT_VERSION = Pattern.compile(
			"^(claude-(?:sonnet|opus|haiku)-)(\\d+)\\.(\\d+)$");

	private static final Set<String> HIDDEN_MODELS = Set.of(
			"auto",
			"claude-3.7-sonnet"
	);

	@Cacheable("kiroModels")
	public List<Map<String, Object>> listModels(StoredAccount account) throws Exception {

		String region = regionFromProfileArn(account.profileArn());
		if (region == null) {
			region = account.apiRegion() != null ? account.apiRegion()
			         : account.region() != null ? account.region()
			         : config.apiRegion();
		}
		String baseUrl = "https://q." + region + ".amazonaws.com";
		String url =
				baseUrl + "/ListAvailableModels?origin=AI_EDITOR&maxResults=50"
				+ "&profileArn=" + URLEncoder.encode(
						account.profileArn(),
						StandardCharsets.UTF_8
				);

		String token = account.accessToken();
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .GET()
		                                 .header(
				                                 "Authorization",
				                                 "Bearer " + token
		                                 )
		                                 .header("Accept", "application/json")
		                                 .header(
				                                 "User-Agent",
				                                 "aws-sdk-js/1.0.0 ua/2.1 os/darwin lang/js md/nodejs#22.0.0 api/codewhispererruntime#1.0.0 m/N,E KiroIDE-0.7.45"
		                                 )
		                                 .header(
				                                 "x-amz-user-agent",
				                                 "aws-sdk-js/1.0.0 KiroIDE-0.7.45"
		                                 )
		                                 .header(
				                                 "x-amzn-codewhisperer-optout",
				                                 "true"
		                                 )
		                                 .build();

		HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			throw new IOException(
					"ListAvailableModels HTTP " + response.statusCode() +
					": " + response.body());
		}
		log.debug("[Models] ListAvailableModels region={} status={}", region, response.statusCode());
		ModelsResponse result = new JsonMapper().readValue(
				response.body(),
				ModelsResponse.class
		);
		List<Map<String, Object>> raw = Optional.ofNullable(result.models())
		                                        .orElse(List.of());

		Set<String> disabled = config.disabledModelsSet();
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<Map<String, Object>> models = new ArrayList<>();

		for (Map<String, Object> m : raw) {
			String id = (String) m.get("modelId");
			if (id == null || disabled.contains(id) ||
			    HIDDEN_MODELS.contains(id)) {
				continue;
			}
			if (seen.add(id)) {
				models.add(Map.of("id", id));
			}
			Matcher mat = DOT_VERSION.matcher(id);
			if (mat.matches()) {
				String hyphenated =
						mat.group(1) + mat.group(2) + "-" + mat.group(3);
				if (!disabled.contains(hyphenated) &&
				    seen.add(hyphenated)) {
					models.add(Map.of("id", hyphenated));
				}
			}
		}

		return models;
	}

	private record ModelsResponse(List<Map<String, Object>> models) {}

	private static String regionFromProfileArn(String profileArn) {
		if (profileArn == null || profileArn.isBlank()) return null;
		String[] parts = profileArn.split(":", 6);
		if (parts.length < 6 || !"arn".equals(parts[0]) ||
		    !"codewhisperer".equals(parts[2])) {
			return null;
		}
		String region = parts[3].trim();
		return region.isEmpty() ? null : region;
	}
}
