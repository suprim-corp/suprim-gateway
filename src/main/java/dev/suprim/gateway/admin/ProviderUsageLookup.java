package dev.suprim.gateway.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads one account's usage from whichever provider owns it.
 * <p>
 * The providers page polls each card on its own, so several browser tabs on the same account can
 * ask at once. Results are cached for slightly less than one poll interval: every poll still sees
 * fresh figures, but concurrent or duplicate asks collapse into a single upstream call instead of
 * one per open tab.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ProviderUsageLookup {

	/** Just under the page's poll interval, so each poll sees fresh figures without stacking calls. */
	private static final Duration FRESH_FOR = Duration.ofSeconds(4);

	/** Providers that expose a usage endpoint at all. The rest are never worth a request. */
	private static final Set<Provider> REPORTS_USAGE =
			Set.of(Provider.KIRO, Provider.CODEX, Provider.ANTIGRAVITY);

	private final KiroAuthManager kiroAuthManager;
	private final CodexAuthManager codexAuthManager;
	private final AntigravityAuthManager antigravityAuthManager;

	private final Cache<String, Map<String, Object>> cache =
			Caffeine.newBuilder()
			        .expireAfterWrite(FRESH_FOR)
			        .maximumSize(200)
			        .build();

	/**
	 * Usage for one account, or an empty map when there is none to report. Never throws: a failed
	 * lookup is rendered as "no usage" rather than breaking the page.
	 * <p>
	 * An account with no access token cannot report anything, and asking would only spend a token
	 * refresh attempt, so it is answered without a call.
	 */
	public Map<String, Object> forAccount(StoredAccount account) {
		if (account.accessToken() == null || !reportsUsage(account)) {
			return Map.of();
		}
		return cache.get(cacheKey(account), key -> read(account));
	}

	private Map<String, Object> read(StoredAccount account) {
		try {
			return switch (Provider.valueOf(account.provider())) {
				case KIRO -> kiroAuthManager.getUsageLimits(account);
				case CODEX -> codexAuthManager.getUsageLimits(account);
				case ANTIGRAVITY -> antigravityUsage(account);
				default -> Map.of();
			};
		} catch (Exception e) {
			log.debug(
					"[Usage] lookup failed for {}: {}",
					account.provider(),
					e.getMessage()
			);
			return Map.of();
		}
	}

	private Map<String, Object> antigravityUsage(StoredAccount account) {
		Map<String, Object> usage =
				new LinkedHashMap<>(antigravityAuthManager.getQuota(account));
		String tier = antigravityAuthManager.getSubscriptionTier(account);
		if (tier != null) {
			usage.put("tier", tier);
		}
		return usage;
	}

	/** A provider name the store does not recognise reports nothing rather than failing. */
	private boolean reportsUsage(StoredAccount account) {
		if (account.provider() == null) {
			return false;
		}
		try {
			return REPORTS_USAGE.contains(Provider.valueOf(account.provider()));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Accounts are identified by their own fields rather than store position, so reordering or
	 * deleting an account does not hand its cached figures to a different one. The store hands
	 * back fresh records on every read, so the key has to come from the content: the name when
	 * there is one, otherwise whichever credential identifies the account.
	 */
	private String cacheKey(StoredAccount account) {
		String identity = Optional.ofNullable(account.name())
		                          .or(() -> Optional.ofNullable(account.clientId()))
		                          .or(() -> Optional.ofNullable(account.accessToken()))
		                          .orElse("unidentified");
		return account.provider() + "|" + identity;
	}
}
