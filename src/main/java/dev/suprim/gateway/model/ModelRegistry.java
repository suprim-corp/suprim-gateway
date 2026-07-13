package dev.suprim.gateway.model;

import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.proxy.KiroHttpClient;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class ModelRegistry {

	private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);
	private static final Pattern DOT_VERSION = Pattern.compile(
			"^(claude-(?:sonnet|opus|haiku)-)(\\d+)\\.(\\d+)$");

	private static final List<String> FALLBACK_MODELS = List.of(
			"auto",
			"claude-sonnet-4",
			"claude-sonnet-4.5",
			"claude-sonnet-4.6",
			"claude-opus-4",
			"claude-opus-4.5",
			"claude-opus-4.6",
			"claude-haiku-4.5",
			"claude-3.7-sonnet",
			"deepseek-3.2",
			"glm-5",
			"minimax-m2.5",
			"minimax-m2.1",
			"qwen3-coder-next"
	);

	private static final Set<String> HIDDEN_MODELS = Set.of(
			"auto",
			"claude-3.7-sonnet"
	);

	private final KiroHttpClient client;
	private final AppConfig config;
	private final CredentialStore credentialStore;
	private final AntigravityAuthManager antigravityAuthManager;
	private final XaiAuthManager xaiAuthManager;
	private final List<String> cachedModels = new CopyOnWriteArrayList<>(
			FALLBACK_MODELS);

	@PostConstruct
	void init() {
		refreshModels();
	}

	@Scheduled(fixedDelay = 300_000)
	public void refreshModels() {
		try {
			String url = client.getListModelsUrl();
			log.info("[Models] Fetching from {}", url);
			KiroHttpClient.KiroResponse res = client.request(
					"GET",
					url,
					null,
					false
			);
			if (res.status() == 200) {
				try (InputStream is = res.body()) {
					String json = new String(is.readAllBytes());
					List<String> ids = parseModelIds(json);
					if (!ids.isEmpty()) {
						cachedModels.clear();
						cachedModels.addAll(ids);
						log.info("[Models] {} fetched: {}", ids.size(), ids);
						return;
					}
				}
			}
			log.warn(
					"[Models] API returned status {}, using fallbacks",
					res.status()
			);
		} catch (Exception e) {
			log.warn(
					"[Models] Failed to fetch: {}, using fallbacks",
					e.getMessage()
			);
		}
		if (cachedModels.isEmpty()) {
			cachedModels.addAll(FALLBACK_MODELS);
		}
	}

	public List<String> getAvailableModels() {
		Set<String> disabled = config.disabledModelsSet();
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (String id : cachedModels) {
			if (disabled.contains(id) || HIDDEN_MODELS.contains(id)) continue;
			result.add(id);
			Matcher m = DOT_VERSION.matcher(id);
			if (m.matches()) {
				String hyphenated = m.group(1) + m.group(2) + "-" + m.group(3);
				if (!disabled.contains(hyphenated)) result.add(hyphenated);
			}
		}
		return new ArrayList<>(result);
	}

	public List<ModelForListingApi> getAllModelsForApi() {
		List<ModelForListingApi> result = new ArrayList<>();
		long now = System.currentTimeMillis() / 1000;

		getAvailableModels().forEach(id ->
				result.add(
						ModelForListingApi.builder()
						                  .id(id)
						                  .object("model")
						                  .ownedBy(Provider.KIRO.name())
						                  .created(now)
						                  .build()
				)
		);

		LinkedHashSet<String> seen = new LinkedHashSet<>();
		for (StoredAccount account : credentialStore.load()) {
			try {
				switch (Provider.valueOf(account.provider())) {
					case ANTIGRAVITY -> antigravityAuthManager
							.listModels(account)
							.forEach(m -> {
								String id = "ag/" + m.get("id");
								if (seen.add(id)) {
									result.add(
											ModelForListingApi
													.builder()
													.id(id)
													.created(now)
													.ownedBy(Provider.ANTIGRAVITY.name())
													.object("model")
													.build()
									);
								}
							});
					case XAI -> xaiAuthManager
							.listModels(account)
							.forEach(m -> {
								String id = (String) m.get("id");
								if (seen.add(id)) {
									result.add(
											ModelForListingApi
													.builder()
													.id(id)
													.created(now)
													.ownedBy(Provider.XAI.name())
													.object("model")
													.build()
									);
								}
							});
					default -> {}
				}
			} catch (Exception ignored) {}
		}

		return result;
	}

	public List<ModelInfo> getModelsForProvider(StoredAccount account) {
		return switch (Provider.valueOf(account.provider())) {
			case KIRO -> getAvailableModels().stream()
			                                 .map(ModelInfo::of)
			                                 .toList();
			case ANTIGRAVITY -> safeListModels(
					() -> antigravityAuthManager.listModels(account)
					                            .stream()
					                            .map(m -> {
						                            Object quota = m.get("quota");
						                            if (quota instanceof Integer q) {
							                            return ModelInfo.of(
									                            "ag/" +
									                            m.get("id"),
									                            q
							                            );
						                            }

						                            return ModelInfo.of(
								                            "ag/" +
								                            m.get("id")
						                            );
					                            })
					                            .toList()
			);
			case XAI -> safeListModels(
					() -> xaiAuthManager.listModels(account)
					                    .stream()
					                    .map(m -> ModelInfo.of(
							                    (String) m.get("id")
					                    ))
					                    .toList()
			);
			default -> List.of();
		};
	}

	private List<ModelInfo> safeListModels(ModelsFetcher fetcher) {
		try {
			return fetcher.fetch();
		} catch (Exception e) {
			return List.of();
		}
	}

	@FunctionalInterface
	private interface ModelsFetcher {
		List<ModelInfo> fetch() throws Exception;
	}

	private List<String> parseModelIds(String json) {
		List<String> ids = new ArrayList<>();
		int idx = 0;
		String key = "\"modelId\"";
		while ((idx = json.indexOf(key, idx)) != -1) {
			int colon = json.indexOf(':', idx + key.length());
			int quote1 = json.indexOf('"', colon + 1);
			int quote2 = json.indexOf('"', quote1 + 1);
			if (quote1 != -1 && quote2 != -1) {
				ids.add(json.substring(quote1 + 1, quote2));
			}
			idx = quote2 + 1;
		}
		return ids;
	}

	@Builder
	public record ModelForListingApi(
			String id,
			String object,
			String ownedBy,
			long created
	) {}
}
