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
import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.proxy.ProxyChain;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
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
					"[Kiro] Initialized from credential store: type={}, region={}, apiRegion={}",
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
				"[Kiro] Initialized: type={}, region={}, apiRegion={}",
				authType,
				config.region(),
				config.apiRegion()
		);
	}

	public String getAccessToken() throws Exception {
		if (authType == KiroCredentials.AuthType.API_KEY) {
			return accessToken;
		}
		if (accessToken != null && expiresAt != null && Instant.now().isBefore(
				expiresAt.minusSeconds(600))) {
			return accessToken;
		}
		refresh();
		return accessToken;
	}

	public void forceRefresh() throws Exception {
		if (authType == KiroCredentials.AuthType.API_KEY) return;
		refresh();
	}

	public boolean isApiKeyAuth() {
		return authType == KiroCredentials.AuthType.API_KEY;
	}

	public ImportResult importAccount(ImportRequest req) throws Exception {
		ImportResult result = KiroAccountImporter.execute(
				req, credentialStore, proxyChain.currentClient()
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

			log.info("[Kiro] Refreshing token via {}", authType);
			try {
				doRefresh();
			} catch (Exception e) {
				log.warn(
						"[Kiro] Refresh failed, reloading from Kiro DB: {}",
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
					proxyChain.currentClient()
			);
		} else {
			result = SsoOidcTokenRefresher.refresh(
					refreshToken,
					clientId,
					clientSecret,
					scopes,
					config.region(),
					proxyChain.currentClient()
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
					"[Kiro] Bootstrap refresh failed, not saving to store: {}",
					e.getMessage()
			);
			return;
		}
		saveToStore();
		log.info(
				"[Kiro] Bootstrapped credential store from {}",
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

		boolean isApiKey = "api_key".equals(account.authType());
		String region = regionFromProfileArn(account.profileArn());
		if (region == null) {
			region = account.apiRegion() != null ? account.apiRegion()
					: account.region() != null ? account.region()
					  : config.apiRegion();
		}
		String baseUrl = "us-east-1".equals(region)
				? "https://codewhisperer.us-east-1.amazonaws.com"
				: "https://q." + region + ".amazonaws.com";
		String url =
				baseUrl + "/ListAvailableModels?origin=AI_EDITOR&maxResults=50";
		if (!isApiKey && account.profileArn() != null) {
			url += "&profileArn=" + URLEncoder.encode(
					account.profileArn(),
					StandardCharsets.UTF_8
			);
		}

		String token = account.accessToken();
		HttpRequest.Builder reqBuilder =
				HttpRequest.newBuilder()
				           .uri(URI.create(url))
				           .GET()
				           .header(
						           "Authorization",
						           "Bearer " + token
				           )
				           .header(
						           "Accept",
						           "application/json"
				           )
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
				           );
		if (isApiKey) {
			reqBuilder.header("tokentype", "API_KEY");
		}
		HttpRequest request = reqBuilder.build();

		HttpResponse<String> response = proxyChain.currentClient().send(
				request,
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			throw new IOException(
					"ListAvailableModels HTTP " + response.statusCode() +
					": " + response.body());
		}
		log.debug(
				"[Models] ListAvailableModels region={} status={}",
				region,
				response.statusCode()
		);
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
			Object cost = m.get("rateMultiplier");
			String unit = (String) m.get("rateUnit");
			String name = (String) m.get("modelName");
			Matcher mat = DOT_VERSION.matcher(id);
			if (mat.matches()) {
				String hyphenated =
						mat.group(1) + mat.group(2) + "-" + mat.group(3);
				if (!disabled.contains(hyphenated) &&
				    seen.add(hyphenated)) {
					models.add(
							Map.of(
									"id", hyphenated,
									"cost", cost != null ? cost : 0,
									"unit", unit != null ? unit : "",
									"name", name != null ? name : ""
							)
					);
				}
			} else if (seen.add(id)) {
				models.add(
						Map.of(
								"id", id,
								"cost", cost != null ? cost : 0,
								"unit", unit != null ? unit : "",
								"name", name != null ? name : ""
						)
				);
			}
		}

		return models;
	}

	private record ModelsResponse(List<Map<String, Object>> models) {}

	public Map<String, Object> getUsageLimits(StoredAccount account) {
		try {
			boolean isApiKey = "api_key".equals(account.authType());
			String region = regionFromProfileArn(account.profileArn());
			if (region == null) {
				region = account.region() !=
				         null ? account.region() : config.region();
			}
			String url = String.format(Kiro.Q_HOST_TEMPLATE, region) +
			             Kiro.USAGE_LIMITS_PATH;
			if (!isApiKey && account.profileArn() != null) {
				url += "&profileArn=" + URLEncoder.encode(
						account.profileArn(),
						StandardCharsets.UTF_8
				);
			}
			HttpRequest.Builder reqBuilder =
					HttpRequest.newBuilder()
					           .uri(URI.create(url))
					           .header(
							           "Authorization",
							           "Bearer " +
							           account.accessToken()
					           )
					           .header(
							           "Accept",
							           "application/json"
					           )
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
					           .GET();
			if (isApiKey) {
				reqBuilder.header("tokentype", "API_KEY");
			}
			HttpRequest request = reqBuilder.build();
			HttpResponse<String> response = proxyChain.currentClient().send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() != 200) {
				return Map.of();
			}
			return new JsonMapper().readValue(
					response.body(),
					new TypeReference<>() {}
			);
		} catch (Exception e) {
			log.warn("[Usage] getUsageLimits failed: {}", e.getMessage());
			return Map.of();
		}
	}

	public String fetchEmailForApiKey(String apiKey) {
		try {
			String url = "https://codewhisperer.us-east-1.amazonaws.com"
			             + Kiro.USAGE_LIMITS_PATH;
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(URI.create(url))
			                                 .header(
					                                 "Authorization",
					                                 "Bearer " + apiKey
			                                 )
			                                 .header("tokentype", "API_KEY")
			                                 .header(
					                                 "Accept",
					                                 "application/json"
			                                 )
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
			                                 .GET()
			                                 .build();
			HttpResponse<String> response = proxyChain.currentClient().send(
					request, HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() == 200) {
				JsonMapper mapper = new JsonMapper();
				Map<String, Object> body = mapper.readValue(
						response.body(), new TypeReference<>() {}
				);
				Object userInfo = body.get("userInfo");
				if (userInfo instanceof Map<?, ?> info) {
					Object email = info.get("email");
					if (email instanceof String e && !e.isBlank()) {
						return e;
					}
				}
			}
		} catch (Exception e) {
			log.warn("[Kiro] fetchEmailForApiKey failed: {}", e.getMessage());
		}
		return null;
	}

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
