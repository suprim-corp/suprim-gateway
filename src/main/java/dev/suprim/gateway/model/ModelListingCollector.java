package dev.suprim.gateway.model;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.deepseek.DeepSeekModels;
import dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the aggregated model listing across every configured provider. One
 * account per provider is enough to answer for that provider, so accounts are
 * tried in turn until one responds.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ModelListingCollector {

	private final CredentialStore credentialStore;
	private final KiroAccountModelAvailability kiroModelAvailability;
	private final AntigravityAuthManager antigravityAuthManager;
	private final XaiAuthManager xaiAuthManager;
	private final CodexAuthManager codexAuthManager;

	public List<ModelForListingApi> collect() {
		ModelListing listing = new ModelListing(
				System.currentTimeMillis() / 1000
		);

		kiroModelAvailability.availableModels()
		                     .forEach(modelId -> listing.add(
						                     Provider.KIRO.getPrefix() + modelId,
						                     Provider.KIRO.name(),
						                     modelId
				                     )
		                     );

		accountsByProvider()
				.forEach((provider, accounts) ->
						collectProvider(provider, accounts, listing)
				);
		return listing.models();
	}

	/**
	 * API-key accounts first: they answer without a token refresh, so they are
	 * the cheapest account to ask for a provider's catalog.
	 */
	private Map<Provider, List<StoredAccount>> accountsByProvider() {
		return credentialStore.load()
		                      .stream()
		                      .sorted(Comparator.comparing(account ->
						                      !"api_key".equals(account.authType())
				                      )
		                      )
		                      .flatMap(account -> resolveProvider(account)
				                      .map(provider -> Map.entry(
								                      provider,
								                      account
						                      )
				                      )
				                      .stream()
		                      )
		                      .collect(
				                      Collectors.groupingBy(
						                      Map.Entry::getKey,
						                      LinkedHashMap::new,
						                      Collectors.mapping(
								                      Map.Entry::getValue,
								                      Collectors.toList()
						                      )
				                      )
		                      );
	}

	/**
	 * Empty for accounts with no provider, or one this build does not know.
	 */
	private static Optional<Provider> resolveProvider(StoredAccount account) {
		if (account.provider() == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(Provider.valueOf(account.provider()));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private void collectProvider(
			Provider provider,
			List<StoredAccount> accounts,
			ModelListing listing
	) {
		// Kiro comes from its own availability cache, above
		if (provider == Provider.KIRO) {
			return;
		}
		if (provider == Provider.DEEPSEEK) {
			DeepSeekModels.ALL.forEach(id -> listing.add(
							id,
							Provider.DEEPSEEK.name(),
							DeepSeekModels.displayName(id)
					)
			);
			return;
		}

		for (StoredAccount account : accounts) {
			try {
				addAccountModels(provider, account, listing);
				return;
			} catch (Exception e) {
				log.warn(
						"[Models] {} account '{}' failed, trying next ({})",
						account.provider(),
						Optional.ofNullable(account.name()).orElse("unnamed"),
						e.getClass().getSimpleName()
				);
			}
		}
	}

	private void addAccountModels(
			Provider provider,
			StoredAccount account,
			ModelListing listing
	) throws Exception {
		switch (provider) {
			case ANTIGRAVITY -> antigravityAuthManager.listModels(account)
			                                          .forEach(model ->
					                                          addAntigravity(
							                                          model,
							                                          listing
					                                          )
			                                          );
			case XAI -> xaiAuthManager.listModels(account)
			                          .forEach(model -> {
				                          String id = model.get("id")
				                                           .toString();
				                          listing.add(
						                          id,
						                          Provider.XAI.name(),
						                          displayName(
								                          model,
								                          stripPrefix(
										                          id,
										                          Provider.XAI
								                          )
						                          )
				                          );
			                          });
			case CODEX -> codexAuthManager.listModels(account)
			                              .forEach(model -> {
				                              String id = model.get("id")
				                                               .toString();
				                              listing.add(
						                              id,
						                              Provider.CODEX.name(),
						                              displayName(
								                              model,
								                              stripPrefix(
										                              id,
										                              Provider.CODEX
								                              )
						                              )
				                              );
			                              });
			default -> {}
		}
	}

	private static void addAntigravity(
			Map<String, Object> model,
			ModelListing listing
	) {
		Object id = model.get("id");
		if (id == null) {
			return;
		}
		String modelId = id.toString();
		listing.add(
				Provider.ANTIGRAVITY.getPrefix() + modelId,
				Provider.ANTIGRAVITY.name(),
				"Antigravity | " + displayName(model, modelId)
		);
	}

	private static String stripPrefix(String id, Provider provider) {
		return id.startsWith(provider.getPrefix())
				? id.substring(provider.getPrefix().length())
				: id;
	}

	private static String displayName(
			Map<String, Object> model,
			String fallback
	) {
		return Optional.ofNullable(model.get("displayName"))
		               .map(Object::toString)
		               .orElse(fallback);
	}
}
