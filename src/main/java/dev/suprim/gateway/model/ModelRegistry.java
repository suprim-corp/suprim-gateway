package dev.suprim.gateway.model;

import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.deepseek.DeepSeekModels;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
@Slf4j
public class ModelRegistry {

	private final AppConfig config;
	private final CredentialStore credentialStore;
	private final KiroAuthManager kiroAuthManager;
	private final AntigravityAuthManager antigravityAuthManager;
	private final XaiAuthManager xaiAuthManager;
	private final CodexAuthManager codexAuthManager;

	private final AtomicReference<List<ModelForListingApi>> cachedModels = new AtomicReference<>(List.of());

	@PostConstruct
	void warmUp() {
		refreshCache();
	}

	@Scheduled(fixedDelay = 300_000)
	public void refreshCache() {
		List<ModelForListingApi> models = fetchAllModels();
		cachedModels.set(models);
		log.info("\033[36m[Models]\033[0m Cache refreshed: {} models", models.size());
	}

	public List<ModelForListingApi> getAllModelsForApi() {
		return cachedModels.get();
	}

	private List<ModelForListingApi> fetchAllModels() {
		List<ModelForListingApi> result = new ArrayList<>();
		long now = System.currentTimeMillis() / 1000;

		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<StoredAccount> accounts =
				credentialStore.load()
				               .stream()
				               .collect(
						               Collectors.toMap(
								               StoredAccount::provider,
								               a -> a,
								               (first, second) ->
										               "api_key".equals(second.authType()) ? second : first
						               )
				               )
				               .values()
				               .stream()
				               .toList();

		for (StoredAccount account : accounts) {
			try {
				switch (Provider.valueOf(account.provider())) {
					case KIRO ->
							kiroAuthManager.listModels(account).forEach(m -> {
								String modelId = (String) m.get("id");
								String id = Provider.KIRO.getPrefix() + modelId;
								if (modelId != null && seen.add(id)) {
									result.add(
											ModelForListingApi.builder()
											                  .id(id)
											                  .object("model")
											                  .ownedBy(Provider.KIRO.name())
											                  .created(now)
											                  .displayName((String) m.get(
													                  "name"))
											                  .build()
									);
								}
							});
					case ANTIGRAVITY -> antigravityAuthManager
							.listModels(account)
							.forEach(m -> {
								String modelId = (String) m.get("id");
								String id = Provider.ANTIGRAVITY.getPrefix() +
								            modelId;
								if (seen.add(id)) {
									String label = "Antigravity | " +
									               Optional.ofNullable((String) m.get(
											                       "displayName"))
									                       .orElse(modelId);
									result.add(
											ModelForListingApi
													.builder()
													.id(id)
													.created(now)
													.ownedBy(Provider.ANTIGRAVITY.name())
													.displayName(label)
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
									String displayName =
											Optional.ofNullable(
													        (String) m.get("displayName")
											        )
											        .orElse(
													        id.startsWith(
															        "grok/")
															        ? id.substring(
															        5) : id
											        );
									result.add(
											ModelForListingApi
													.builder()
													.id(id)
													.created(now)
													.ownedBy(Provider.XAI.name())
													.displayName(displayName)
													.object("model")
													.build()
									);
								}
							});
					case CODEX -> codexAuthManager
							.listModels(account)
							.forEach(m -> {
								String id = (String) m.get("id");
								if (seen.add(id)) {
									String displayName =
											Optional.ofNullable(
													        (String) m.get("displayName")
											        )
											        .orElse(
													        id.startsWith(
															        Provider.CODEX.getPrefix()
													        ) ? id.substring(6) : id
											        );
									result.add(
											ModelForListingApi
													.builder()
													.id(id)
													.created(now)
													.ownedBy(Provider.CODEX.name())
													.displayName(displayName)
													.object("model")
													.build()
									);
								}
							});
					case DEEPSEEK -> DeepSeekModels.ALL.forEach(id -> {
						if (seen.add(id)) {
							result.add(
									ModelForListingApi.builder()
									                  .id(id)
									                  .object("model")
									                  .ownedBy(Provider.DEEPSEEK.name())
									                  .created(now)
									                  .displayName(DeepSeekModels.displayName(id))
									                  .build()
							);
						}
					});
					default -> {}
				}
			} catch (Exception e) {
				log.warn("[Models] Failed to load models for {}: {}", account.provider(), e.getMessage());
			}
		}

		return result;
	}

	public List<ModelInfo> getModelsForProvider(StoredAccount account) {
		return switch (Provider.valueOf(account.provider())) {
			case KIRO -> safeListModels(
					() -> kiroAuthManager.listModels(account)
					                     .stream()
					                     .map(m -> {
						                     String id =
								                     Provider.KIRO.getPrefix() +
								                     m.get("id");
						                     Object cost = m.get("cost");
						                     String unit = (String) m.get("unit");
						                     if (cost instanceof Number c &&
						                         unit != null &&
						                         !unit.isEmpty()) {
							                     return ModelInfo.of(
									                     id,
									                     c.doubleValue(),
									                     unit
							                     );
						                     }
						                     return ModelInfo.of(id);
					                     })
					                     .toList()
			);
			case ANTIGRAVITY -> safeListModels(
					() -> antigravityAuthManager.listModels(account)
					                            .stream()
					                            .map(m -> {
								                            Object quota = m.get("quota");
								                            if (quota instanceof Integer q) {
									                            return ModelInfo.of(
											                            Provider.ANTIGRAVITY.getPrefix() +
											                            m.get("id"),
											                            q
									                            );
								                            }

								                            return ModelInfo.of(
										                            Provider.ANTIGRAVITY.getPrefix() +
										                            m.get("id")
								                            );
							                            }
					                            )
					                            .toList()
			);
			case XAI -> safeListModels(
					() -> xaiAuthManager.listModels(account)
					                    .stream()
					                    .map(m -> ModelInfo.of(
									                    (String) m.get("id")
							                    )
					                    )
					                    .toList()
			);
			case CODEX -> safeListModels(
					() -> codexAuthManager.listModels(account)
					                      .stream()
					                      .map(m -> ModelInfo.of(
									                      (String) m.get("id")
							                      )
					                      )
					                      .toList()
			);
			default -> List.of();
		};
	}

	private List<ModelInfo> safeListModels(ModelsFetcher fetcher) {
		try {
			return fetcher.fetch();
		} catch (Exception e) {
			log.warn("[Models] listModels failed: {}", e.getMessage());
			return List.of();
		}
	}

	@FunctionalInterface
	private interface ModelsFetcher {
		List<ModelInfo> fetch() throws Exception;
	}

	@Builder
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ModelForListingApi(
			String id,
			String object,
			String ownedBy,
			long created,
			String displayName
	) {}
}
