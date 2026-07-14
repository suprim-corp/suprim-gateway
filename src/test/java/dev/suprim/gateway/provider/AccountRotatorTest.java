package dev.suprim.gateway.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountRotatorTest {

	private StoredAccount account(String name, String provider) {
		return StoredAccount.builder().name(name).provider(provider).build();
	}

	@Test
	void next_cyclesThroughAccountsInOrder() {
		CredentialStore store = mock(CredentialStore.class);
		when(store.findAllByProvider("xai")).thenReturn(List.of(
				account("A", "xai"),
				account("B", "xai"),
				account("C", "xai")
		));
		AccountRotator rotator = new AccountRotator(store);

		assertEquals("A", rotator.next("xai").name());
		assertEquals("B", rotator.next("xai").name());
		assertEquals("C", rotator.next("xai").name());
		assertEquals("A", rotator.next("xai").name());
	}

	@Test
	void next_singleAccount_alwaysReturnsSame() {
		CredentialStore store = mock(CredentialStore.class);
		when(store.findAllByProvider("xai")).thenReturn(List.of(
				account("only", "xai")
		));
		AccountRotator rotator = new AccountRotator(store);

		assertEquals("only", rotator.next("xai").name());
		assertEquals("only", rotator.next("xai").name());
		assertEquals("only", rotator.next("xai").name());
	}

	@Test
	void next_emptyProvider_throwsIllegalState() {
		CredentialStore store = mock(CredentialStore.class);
		when(store.findAllByProvider("none")).thenReturn(List.of());
		AccountRotator rotator = new AccountRotator(store);

		assertThrows(IllegalStateException.class, () -> rotator.next("none"));
	}

	@Test
	void next_perProviderIsolation() {
		CredentialStore store = mock(CredentialStore.class);
		when(store.findAllByProvider("xai")).thenReturn(List.of(
				account("X1", "xai"),
				account("X2", "xai")
		));
		when(store.findAllByProvider("antigravity")).thenReturn(List.of(
				account("A1", "antigravity"),
				account("A2", "antigravity")
		));
		AccountRotator rotator = new AccountRotator(store);

		assertEquals("X1", rotator.next("xai").name());
		assertEquals("A1", rotator.next("antigravity").name());
		assertEquals("X2", rotator.next("xai").name());
		assertEquals("A2", rotator.next("antigravity").name());
	}
}
