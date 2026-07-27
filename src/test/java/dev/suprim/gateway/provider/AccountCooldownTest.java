package dev.suprim.gateway.provider;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountCooldownTest {

	@Test
	void coolDown_lastsThirtyMinutes() {
		MutableClock clock = new MutableClock(Instant.parse("2026-07-23T00:00:00Z"));
		AccountCooldown cooldown = new AccountCooldown(clock);
		StoredAccount account = StoredAccount.builder().name("account").build();

		cooldown.coolDown(account);

		assertTrue(cooldown.isCoolingDown(account));
		clock.advanceSeconds(29 * 60);
		assertTrue(cooldown.isCoolingDown(account));
		clock.advanceSeconds(60);
		assertFalse(cooldown.isCoolingDown(account));
	}

	@Test
	void duration_matchesTheCooldownApplied() {
		assertEquals(Duration.ofMinutes(30), AccountCooldown.duration());
	}

	@Test
	void coolDown_doesNotAffectOtherAccounts() {
		AccountCooldown cooldown = new AccountCooldown(Clock.systemUTC());
		StoredAccount limited = StoredAccount.builder().name("limited").build();
		StoredAccount healthy = StoredAccount.builder().name("healthy").build();

		cooldown.coolDown(limited);

		assertTrue(cooldown.isCoolingDown(limited));
		assertFalse(cooldown.isCoolingDown(healthy));
	}

	@Test
	void accountKey_fallsBackToClientId() {
		AccountCooldown cooldown = new AccountCooldown(Clock.systemUTC());
		StoredAccount account = StoredAccount.builder().clientId("client-id").build();

		cooldown.coolDown(account);

		assertTrue(cooldown.isCoolingDown(account));
	}

	@Test
	void isCoolingDown_isFalseWhenAccountHasNoIdentity() {
		AccountCooldown cooldown = new AccountCooldown(Clock.systemUTC());
		StoredAccount anonymous = StoredAccount.builder().build();

		cooldown.coolDown(anonymous);

		assertFalse(cooldown.isCoolingDown(anonymous));
	}

	/**
	 * Accounts from different providers are distinct entries, so one provider's rate limit
	 * does not sideline another's account.
	 */
	@Test
	void coolDown_keepsProvidersIndependent() {
		AccountCooldown cooldown = new AccountCooldown(Clock.systemUTC());
		StoredAccount codex = StoredAccount.builder()
		                                   .name("user@example.com")
		                                   .provider("CODEX")
		                                   .build();
		StoredAccount antigravity = StoredAccount.builder()
		                                         .name("other@example.com")
		                                         .provider("ANTIGRAVITY")
		                                         .build();

		cooldown.coolDown(codex);

		assertTrue(cooldown.isCoolingDown(codex));
		assertFalse(cooldown.isCoolingDown(antigravity));
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
