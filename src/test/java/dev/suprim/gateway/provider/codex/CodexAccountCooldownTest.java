package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.provider.StoredAccount;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAccountCooldownTest {

	@Test
	void coolDown_expiresAfterSixHours() {
		Instant now = Instant.parse("2026-07-23T00:00:00Z");
		StoredAccount account = StoredAccount.builder().name("account").build();
		MutableClock clock = new MutableClock(now);
		CodexAccountCooldown cooldown = new CodexAccountCooldown(clock);

		cooldown.coolDown(account);

		assertTrue(cooldown.isCoolingDown(account));
		clock.advanceSeconds(6 * 60 * 60);
		assertFalse(cooldown.isCoolingDown(account));
	}

	@Test
	void coolDown_doesNotAffectOtherAccounts() {
		Clock clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
		CodexAccountCooldown cooldown = new CodexAccountCooldown(clock);
		StoredAccount limited = StoredAccount.builder().name("limited").build();
		StoredAccount healthy = StoredAccount.builder().name("healthy").build();

		cooldown.coolDown(limited);

		assertTrue(cooldown.isCoolingDown(limited));
		assertFalse(cooldown.isCoolingDown(healthy));
	}

	@Test
	void accountKey_fallsBackToClientId() {
		CodexAccountCooldown cooldown = new CodexAccountCooldown(Clock.systemUTC());
		StoredAccount account = StoredAccount.builder().clientId("client-id").build();

		cooldown.coolDown(account);

		assertTrue(cooldown.isCoolingDown(account));
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advanceSeconds(long seconds) {
			instant = instant.plusSeconds(seconds);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
