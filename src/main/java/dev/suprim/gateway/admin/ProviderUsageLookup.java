package dev.suprim.gateway.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.UsageFailure;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.antigravity.AntigravityQuota;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
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

	/**
	 * Just under the page's poll interval, so each poll sees fresh figures without stacking calls.
	 */
	private static final Duration FRESH_FOR = Duration.ofSeconds(4);

	/**
	 * A rejected credential stays rejected until someone re-authorises it, so it is held longer than
	 * live figures: polling a refused upstream every few seconds achieves nothing. The window is
	 * still short enough that the page catches up on its own after a re-auth.
	 */
	private static final Duration REJECTION_HELD_FOR = Duration.ofSeconds(30);

	/**
	 * Providers that expose a usage endpoint at all. The rest are never worth a request.
	 */
	private static final Set<Provider> REPORTS_USAGE =
			Set.of(Provider.KIRO, Provider.CODEX, Provider.ANTIGRAVITY);

	private final KiroAuthManager kiroAuthManager;
	private final CodexAuthManager codexAuthManager;
	private final AntigravityAuthManager antigravityAuthManager;

	/**
	 * Providers normalize usage into their own record; Kiro relays its upstream body as-is. A tree
	 * is what those two have in common, so it is what the cache and the page speak.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Cache<String, JsonNode> cache =
			Caffeine.newBuilder()
			        .expireAfter(
					        Expiry.writing(
							        (String key, JsonNode usage) -> retentionFor(
									        usage
							        )
					        )
			        )
			        .maximumSize(200)
			        .build();

	/**
	 * How long one result stays fresh.
	 * <p>
	 * A rejected credential stays rejected until someone re-authorises it, so it is held longer than
	 * live figures: re-asking a refused upstream every few seconds achieves nothing. The window is
	 * still short enough that the page recovers on its own after a re-auth.
	 */
	static Duration retentionFor(JsonNode usage) {
		return UsageFailure.isUnauthorized(usage) ? REJECTION_HELD_FOR : FRESH_FOR;
	}

	/**
	 * Usage for one account, or an empty object when there is none to report. Never throws: a
	 * failed lookup is rendered as "no usage" rather than breaking the page.
	 * <p>
	 * An account with no access token cannot report anything, and asking would only spend a token
	 * refresh attempt, so it is answered without a call.
	 */
	public JsonNode forAccount(StoredAccount account) {
		if (account.accessToken() == null || !reportsUsage(account)) {
			return MAPPER.createObjectNode();
		}
		return cache.get(cacheKey(account), key -> read(account));
	}

	private JsonNode read(StoredAccount account) {
		try {
			return switch (Provider.valueOf(account.provider())) {
				case KIRO -> MAPPER.valueToTree(
						kiroAuthManager.getUsageLimits(
								account
						)
				);
				case CODEX ->
						MAPPER.valueToTree(codexAuthManager.getUsageLimits(
										account
								)
						);
				case ANTIGRAVITY -> antigravityUsage(account);
				default -> MAPPER.createObjectNode();
			};
		} catch (Exception e) {
			log.debug(
					"[Usage] lookup failed for {}: {}",
					account.provider(),
					e.getMessage()
			);
			return MAPPER.createObjectNode();
		}
	}

	/**
	 * Antigravity reports quota and subscription tier from two separate endpoints, so they are
	 * stitched together here. A missing tier is omitted rather than sent as null.
	 */
	private JsonNode antigravityUsage(StoredAccount account) {
		AntigravityQuota quota = antigravityAuthManager.getQuota(account);
		String tier = antigravityAuthManager.getSubscriptionTier(account);
		return MAPPER.valueToTree(tier != null ? quota.withTier(tier) : quota);
	}

	/**
	 * A provider name the store does not recognise reports nothing rather than failing.
	 */
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
