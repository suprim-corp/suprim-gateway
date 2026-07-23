package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.provider.StoredAccount;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
class CodexAccountCooldown {

	private static final Duration COOLDOWN = Duration.ofHours(1);

	private final Clock clock;
	private final ConcurrentHashMap<String, Instant> cooldowns = new ConcurrentHashMap<>();

	CodexAccountCooldown() {
		this(Clock.systemUTC());
	}

	CodexAccountCooldown(Clock clock) {
		this.clock = clock;
	}

	boolean isCoolingDown(StoredAccount account) {
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

	void coolDown(StoredAccount account) {
		String accountKey = accountKey(account);
		if (accountKey != null) {
			cooldowns.put(accountKey, clock.instant().plus(COOLDOWN));
		}
	}

	String accountKey(StoredAccount account) {
		return Optional.ofNullable(account.name()).orElse(account.clientId());
	}
}
