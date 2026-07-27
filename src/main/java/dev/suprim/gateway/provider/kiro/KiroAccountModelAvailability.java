package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class KiroAccountModelAvailability {

	private final CredentialStore credentialStore;
	private final ApplicationEventPublisher eventPublisher;
	private final KiroAuthManager kiroAuthManager;
	private final ModelResolver modelResolver;
	private final CacheManager cacheManager;
	private final Map<String, Set<String>> modelsByAccount = new ConcurrentHashMap<>();

	/**
	 * The full upstream entry per exposed model id, keyed by canonical id. Routing only needs
	 * the ids in {@link #modelsByAccount}; this keeps the capability fields alongside them so
	 * the aggregated listing does not have to re-fetch. Accounts share the map because Kiro
	 * reports the same capabilities for a model regardless of which account asked.
	 */
	private final Map<String, Map<String, Object>> detailsByModel = new ConcurrentHashMap<>();
	private final Set<String> completedAccounts = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean refreshing = new AtomicBoolean();

	@EventListener(ApplicationReadyEvent.class)
	@Async
	public void warmUp() {
		refresh();
	}

	@Scheduled(fixedDelay = 300_000)
	public void refresh() {
		if (!refreshing.compareAndSet(false, true)) {
			return;
		}
		try {
			for (StoredAccount account : credentialStore.findAllByProvider(Provider.KIRO.name())) {
				refresh(account);
			}
			eventPublisher.publishEvent(KiroModelsRefreshedEvent.builder().build());
		} finally {
			refreshing.set(false);
		}
	}

	public void refresh(StoredAccount account) {
		String key = accountKey(account);
		try {
			Set<String> models = fetchAndIndex(account);
			modelsByAccount.put(key, models);
			completedAccounts.add(key);
			log.info(
					LogTag.KIRO + "Cached {} models for account {}",
					models.size(),
					displayName(account)
			);
		} catch (Exception e) {
			completedAccounts.add(key);
			log.warn(
					LogTag.KIRO + "Model cache failed for {}: {}",
					displayName(account),
					e.getMessage()
			);
		}
	}

	public List<StoredAccount> eligibleAccounts(
			String model,
			List<StoredAccount> accounts
	) {
		return accounts.stream()
		               .filter(account -> modelsByAccount.getOrDefault(
				                                                 accountKey(account),
				                                                 Set.of()
		                                                 )
		                                                 .contains(model)
		               )
		               .toList();
	}

	public boolean isWarmUpComplete(List<StoredAccount> accounts) {
		return accounts.stream()
		               .map(KiroAccountModelAvailability::accountKey)
		               .allMatch(completedAccounts::contains);
	}

	public void invalidateModel(StoredAccount account, String model) {
		modelsByAccount.computeIfPresent(
				accountKey(account),
				(key, models) -> models.stream()
				                       .filter(id -> !id.equals(model))
				                       .collect(Collectors.toUnmodifiableSet())
		);
		Optional.ofNullable(cacheManager.getCache("kiroModels"))
		        .ifPresent(cache -> cache.evict(accountKey(account)));
	}

	public Set<String> availableModels() {
		return modelsByAccount.values().stream().flatMap(Set::stream).collect(
				Collectors.toUnmodifiableSet());
	}

	public Set<String> modelsForAccount(StoredAccount account) {
		return modelsByAccount.getOrDefault(accountKey(account), Set.of());
	}

	/**
	 * Models for one account, fetching upstream when the periodic refresh has
	 * not populated the cache yet or produced nothing. Surfaces the upstream
	 * failure instead of reporting an account as having no models at all.
	 */
	public Set<String> modelsForAccountOrFetch(StoredAccount account)
			throws Exception {
		Set<String> cached = modelsForAccount(account);
		if (!cached.isEmpty()) {
			return cached;
		}
		Set<String> models = fetchAndIndex(account);
		String key = accountKey(account);
		modelsByAccount.put(key, models);
		completedAccounts.add(key);
		return models;
	}

	/**
	 * Fetches one account's models, returning their canonical ids and recording each model's
	 * full upstream entry in {@link #detailsByModel} on the way through.
	 */
	private Set<String> fetchAndIndex(StoredAccount account) throws Exception {
		Set<String> canonicalIds = new LinkedHashSet<>();
		for (Map<String, Object> model : kiroAuthManager.listModels(account)) {
			if (!(model.get("id") instanceof String id)) {
				continue;
			}
			String canonical = modelResolver.canonicalize(id);
			canonicalIds.add(canonical);
			detailsByModel.put(canonical, model);
		}
		return Set.copyOf(canonicalIds);
	}

	/**
	 * The upstream entry for one canonical model id, or an empty map when no refresh has seen
	 * that model yet. Callers read capability fields out of it; see
	 * {@code KiroAuthManager#listModels} for the keys.
	 */
	public Map<String, Object> modelDetails(String canonicalModelId) {
		return detailsByModel.getOrDefault(canonicalModelId, Map.of());
	}

	public static String accountKey(StoredAccount account) {
		if (account.profileArn() != null && !account.profileArn().isBlank()) {
			return "arn:" + account.profileArn();
		}
		if (account.name() != null && !account.name().isBlank()) {
			return "name:" + account.name();
		}
		return "token:" + sha256(account.accessToken());
	}

	private static String displayName(StoredAccount account) {
		return Optional.ofNullable(account.name()).orElse("unnamed");
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
			                             .digest(
					                             Optional.ofNullable(value)
					                                     .orElse("")
					                                     .getBytes(
							                                     StandardCharsets.UTF_8
					                                     )
			                             );
			return HexFormat.of().formatHex(digest);
		} catch (Exception e) {
			throw new IllegalStateException(
					"Cannot hash Kiro account identity",
					e
			);
		}
	}
}
