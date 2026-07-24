package dev.suprim.gateway.model;

import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.deepseek.DeepSeekModels;
import dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability;
import dev.suprim.gateway.provider.kiro.KiroModelsRefreshedEvent;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
@Slf4j
public class ModelRegistry {

	private final AppConfig config;
	private final CredentialStore credentialStore;
	private final KiroAccountModelAvailability kiroModelAvailability;
	private final AntigravityAuthManager antigravityAuthManager;
	private final XaiAuthManager xaiAuthManager;
	private final CodexAuthManager codexAuthManager;
	private final AtomicReference<List<ModelForListingApi>> cachedModels =
			new AtomicReference<>(List.of());

	@PostConstruct
	void warmUp() {
		refreshCache();
	}

	@Scheduled(fixedDelay = 300_000)
	public void refreshCache() {
		List<ModelForListingApi> models = fetchAllModels();
		cachedModels.set(models);
		log.info(
				"\033[36m[Models]\033[0m Cache refreshed: {} models",
				models.size()
		);
	}

	public List<ModelForListingApi> getAllModelsForApi() {
		return cachedModels.get();
	}

	@EventListener(KiroModelsRefreshedEvent.class)
	void refreshKiroModels() {
		refreshCache();
	}

	private List<ModelForListingApi> fetchAllModels() {
		List<ModelForListingApi> result = new ArrayList<>();
		long now = System.currentTimeMillis() / 1000;
		LinkedHashSet<String> seen = new LinkedHashSet<>();

		kiroModelAvailability.availableModels()
		                     .forEach(modelId -> addModel(
						                     result,
						                     seen,
						                     Provider.KIRO.getPrefix() + modelId,
						                     Provider.KIRO.name(),
						                     modelId,
						                     now
				                     )
		                     );

		Map<String, List<StoredAccount>> byProvider =
				credentialStore.load()
				               .stream()
				               .filter(account ->
						               account.provider() != null
				               )
				               .sorted(
						               Comparator.comparing(
								               account -> !"api_key".equals(
										               account.authType()
								               )
						               )
				               )
				               .collect(
						               Collectors.groupingBy(
								               StoredAccount::provider
						               )
				               );

		for (Map.Entry<String, List<StoredAccount>> entry : byProvider.entrySet()) {
			Provider provider;
			try {
				provider = Provider.valueOf(entry.getKey());
			} catch (IllegalArgumentException e) {
				continue;
			}
			if (provider == Provider.KIRO) {
				continue;
			}
			if (provider == Provider.DEEPSEEK) {
				DeepSeekModels.ALL.forEach(id -> addModel(
								result,
								seen,
								id,
								Provider.DEEPSEEK.name(),
								DeepSeekModels.displayName(id),
								now
						)
				);
				continue;
			}

			for (StoredAccount account : entry.getValue()) {
				try {
					switch (provider) {
						case ANTIGRAVITY -> antigravityAuthManager.listModels(
								account).forEach(model -> {
							String modelId = model.get("id").toString();
							String id =
									Provider.ANTIGRAVITY.getPrefix() + modelId;
							String name = Optional.ofNullable(
									                      model.get("displayName")
									                           .toString()
							                      )
							                      .orElse(modelId);
							addModel(
									result,
									seen,
									id,
									Provider.ANTIGRAVITY.name(),
									"Antigravity | " + name,
									now
							);
						});
						case XAI -> xaiAuthManager.listModels(account).forEach(
								model -> {
									String id = (String) model.get("id");
									String name = Optional.ofNullable(
											                      model.get("displayName")
											                           .toString()
									                      )
									                      .orElse(id.startsWith(
											                      "grok/") ? id.substring(
											                      5) : id);
									addModel(
											result,
											seen,
											id,
											Provider.XAI.name(),
											name,
											now
									);
								});
						case CODEX ->
								codexAuthManager.listModels(account).forEach(
										model -> {
											String id = (String) model.get("id");
											String name = Optional.ofNullable(
													                      model.get("displayName")
													                           .toString()
											                      )
											                      .orElse(id.startsWith(
													                      Provider.CODEX.getPrefix()) ? id.substring(
													                      6) : id);
											addModel(
													result,
													seen,
													id,
													Provider.CODEX.name(),
													name,
													now
											);
										});
						default -> {}
					}
					break;
				} catch (Exception e) {
					log.warn(
							"[Models] {} account '{}' failed, trying next: {}",
							account.provider(),
							Optional.ofNullable(account.name())
							        .orElse("unnamed"),
							e.getMessage()
					);
				}
			}
		}
		return result;
	}

	private void addModel(
			List<ModelForListingApi> result,
			LinkedHashSet<String> seen,
			String id,
			String provider,
			String displayName,
			long created
	) {
		if (id != null && seen.add(id)) {
			result.add(
					ModelForListingApi.builder()
					                  .id(id)
					                  .object("model")
					                  .ownedBy(provider)
					                  .created(created)
					                  .displayName(displayName)
					                  .build()
			);
		}
	}

	public List<ModelInfo> getModelsForProvider(StoredAccount account) throws Exception {
		return switch (Provider.valueOf(account.provider())) {
			case KIRO -> kiroModelAvailability.modelsForAccount(account)
			                                  .stream()
			                                  .map(id -> ModelInfo.of(
							                                  Provider.KIRO.getPrefix() +
							                                  id
					                                  )
			                                  )
			                                  .toList();
			case ANTIGRAVITY -> antigravityAuthManager.listModels(account)
			                                          .stream()
			                                          .map(model -> {
				                                          Object quota = model.get(
						                                          "quota");
				                                          if (quota instanceof Integer value) {
					                                          return ModelInfo.of(
							                                          Provider.ANTIGRAVITY.getPrefix() +
							                                          model.get(
									                                          "id"),
							                                          value
					                                          );
				                                          }
				                                          return ModelInfo.of(
						                                          Provider.ANTIGRAVITY.getPrefix() +
						                                          model.get("id")
				                                          );
			                                          })
			                                          .toList();
			case XAI -> xaiAuthManager.listModels(account)
			                          .stream()
			                          .map(model -> ModelInfo.of(
							                          model.get("id").toString()
					                          )
			                          )
			                          .toList();
			case CODEX -> codexAuthManager.listModels(account)
			                              .stream()
			                              .map(
					                              model -> ModelInfo.of(
							                              model.get("id")
							                                   .toString()
					                              )
			                              )
			                              .toList();
			default -> List.of();
		};
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
