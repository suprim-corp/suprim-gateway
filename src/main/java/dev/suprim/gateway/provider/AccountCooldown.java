package dev.suprim.gateway.provider;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accounts an upstream has told us to back off from, and until when.
 * <p>
 * Shared by every provider that rotates accounts: an account that answered with a rate limit
 * is skipped for {@link #COOLDOWN} rather than tried again on the next request, so a limited
 * account does not cost every caller an attempt. Entries are evicted lazily, on the read that
 * finds them expired, since a cooled account is only ever asked about when it comes up in the
 * rotation.
 * <p>
 * One instance is shared across providers. That is safe because the keys are account
 * identities, which are unique across providers, and the cooldown means the same thing
 * everywhere: this account is unavailable right now.
 */
@Component
public class AccountCooldown {

	private static final Duration COOLDOWN = Duration.ofMinutes(30);

	private final Clock clock;
	private final ConcurrentHashMap<String, Instant> cooldowns = new ConcurrentHashMap<>();

	public AccountCooldown() {
		this(Clock.systemUTC());
	}

	public AccountCooldown(Clock clock) {
		this.clock = clock;
	}

	/**
	 * How long a cooled account stays skipped. Exposed so callers can say so in a log.
	 */
	public static Duration duration() {
		return COOLDOWN;
	}

	public boolean isCoolingDown(StoredAccount account) {
		String accountKey = accountKey(account);
		if (accountKey == null) {
			return false;
		}
		Instant expiresAt = cooldowns.get(accountKey);
		if (expiresAt == null) {
			return false;
		}
		if (expiresAt.isAfter(clock.instant())) {
			return true;
		}
		cooldowns.remove(accountKey, expiresAt);
		return false;
	}

	public void coolDown(StoredAccount account) {
		String accountKey = accountKey(account);
		if (accountKey != null) {
			cooldowns.put(accountKey, clock.instant().plus(COOLDOWN));
		}
	}

	/**
	 * Identifies an account for cooldown purposes. Accounts imported without a name are
	 * still told apart by their client id.
	 */
	public String accountKey(StoredAccount account) {
		return Optional.ofNullable(account.name()).orElse(account.clientId());
	}
}
