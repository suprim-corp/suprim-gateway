package dev.suprim.gateway.model;

import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability;
import dev.suprim.gateway.provider.kiro.KiroModelNames;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Models available to one specific account. Upstream failures propagate so
 * callers can tell "this account has no models" apart from "the lookup failed".
 */
@RequiredArgsConstructor
@Component
public class ProviderModelCatalog {

	private final KiroAccountModelAvailability kiroModelAvailability;
	private final AntigravityAuthManager antigravityAuthManager;
	private final XaiAuthManager xaiAuthManager;
	private final CodexAuthManager codexAuthManager;

	public List<ModelInfo> forAccount(StoredAccount account) throws Exception {
		return switch (Provider.valueOf(account.provider())) {
			case KIRO -> kiroModels(account);
			case ANTIGRAVITY -> antigravityModels(account);
			case XAI -> prefixedIds(xaiAuthManager.listModels(account));
			case CODEX -> prefixedIds(codexAuthManager.listModels(account));
			default -> List.of();
		};
	}

	private List<ModelInfo> kiroModels(StoredAccount account) throws Exception {
		return kiroModelAvailability.modelsForAccountOrFetch(account)
		                            .stream()
		                            .map(id -> ModelInfo.of(
				                            Provider.KIRO.getPrefix() +
				                            KiroModelNames.exposedId(id)
		                            ))
		                            .toList();
	}

	private List<ModelInfo> antigravityModels(StoredAccount account)
			throws Exception {
		return antigravityAuthManager.listModels(account)
		                             .stream()
		                             .map(ProviderModelCatalog::antigravityModel)
		                             .toList();
	}

	private static ModelInfo antigravityModel(Map<String, Object> model) {
		String id = Provider.ANTIGRAVITY.getPrefix() + model.get("id");
		return model.get("quota") instanceof Integer quota
				? ModelInfo.of(id, quota)
				: ModelInfo.of(id);
	}

	/** Ids from these providers already carry their own prefix. */
	private static List<ModelInfo> prefixedIds(
			List<Map<String, Object>> models
	) {
		return models.stream()
		             .map(model -> ModelInfo.of(model.get("id").toString()))
		             .toList();
	}
}
