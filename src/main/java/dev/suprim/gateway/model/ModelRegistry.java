package dev.suprim.gateway.model;

import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
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

	public List<ModelForListingApi> getAllModelsForApi() {
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
								               (first, second) -> first
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
								String id = (String) m.get("id");
								if (id != null && seen.add(id)) {
									result.add(
											ModelForListingApi.builder()
											                  .id(id)
											                  .object("model")
											                  .ownedBy(Provider.KIRO.name())
											                  .created(now)
											                  .build()
									);
								}
							});
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
			case KIRO -> safeListModels(
					() -> kiroAuthManager.listModels(account)
					                     .stream()
					                     .map(m -> (String) m.get("id"))
					                     .filter(Objects::nonNull)
					                     .map(ModelInfo::of)
					                     .toList()
			);
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

	@Builder
	public record ModelForListingApi(
			String id,
			String object,
			String ownedBy,
			long created
	) {}
}
