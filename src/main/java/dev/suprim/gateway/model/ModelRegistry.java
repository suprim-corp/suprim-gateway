package dev.suprim.gateway.model;

import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.KiroModelsRefreshedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caches the aggregated model listing served by /v1/models, refreshing it on a
 * schedule and whenever Kiro reports new model availability.
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class ModelRegistry {

	private final ModelListingCollector listingCollector;
	private final ProviderModelCatalog providerModelCatalog;
	private final AtomicReference<List<ModelForListingApi>> cachedModels =
			new AtomicReference<>(List.of());

	@PostConstruct
	void warmUp() {
		refreshCache();
	}

	@Scheduled(fixedDelay = 300_000)
	public void refreshCache() {
		List<ModelForListingApi> models = listingCollector.collect();
		cachedModels.set(models);
		log.info(
				"\033[36m[Models]\033[0m Cache refreshed: {} models",
				models.size()
		);
	}

	public List<ModelForListingApi> getAllModelsForApi() {
		return cachedModels.get();
	}

	public List<ModelInfo> getModelsForProvider(StoredAccount account)
			throws Exception {
		return providerModelCatalog.forAccount(account);
	}

	@EventListener(KiroModelsRefreshedEvent.class)
	void refreshKiroModels() {
		refreshCache();
	}
}
