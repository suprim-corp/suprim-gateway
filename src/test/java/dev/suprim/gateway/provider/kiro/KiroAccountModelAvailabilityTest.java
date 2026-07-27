package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KiroAccountModelAvailabilityTest {

	private KiroAuthManager authManager;
	private KiroAccountModelAvailability availability;

	@BeforeEach
	void setUp() {
		authManager = mock(KiroAuthManager.class);
		availability = new KiroAccountModelAvailability(
				mock(CredentialStore.class),
				mock(ApplicationEventPublisher.class),
				authManager,
				new ModelResolver(),
				mock(CacheManager.class)
		);
	}

	private StoredAccount account() {
		return StoredAccount.builder()
		                    .name("kiro-acc")
		                    .provider("KIRO")
		                    .authType("api_key")
		                    .accessToken("ksk_test")
		                    .build();
	}

	@Test
	void modelsForAccountOrFetch_fetchesWhenCacheIsEmpty() throws Exception {
		StoredAccount account = account();
		when(authManager.listModels(account)).thenReturn(List.of(
				Map.of("id", "claude-sonnet-4-20250514"),
				Map.of("id", "claude-opus-4-20250514")
		));

		Set<String> models = availability.modelsForAccountOrFetch(account);

		assertEquals(2, models.size());
		verify(authManager).listModels(account);
	}

	@Test
	void modelsForAccountOrFetch_reusesCacheWithoutCallingUpstreamAgain()
			throws Exception {
		StoredAccount account = account();
		when(authManager.listModels(account)).thenReturn(List.of(
				Map.of("id", "claude-sonnet-4-20250514")
		));

		availability.modelsForAccountOrFetch(account);
		Set<String> second = availability.modelsForAccountOrFetch(account);

		assertEquals(1, second.size());
		verify(authManager, times(1)).listModels(account);
	}

	@Test
	void modelsForAccountOrFetch_propagatesUpstreamFailure() throws Exception {
		StoredAccount account = account();
		when(authManager.listModels(account))
				.thenThrow(new java.io.IOException("Improperly formed request"));

		assertThrows(
				java.io.IOException.class,
				() -> availability.modelsForAccountOrFetch(account)
		);
	}

	@Test
	void modelsForAccount_staysEmptyWithoutFetching() {
		StoredAccount account = account();

		assertTrue(availability.modelsForAccount(account).isEmpty());
		verifyNoInteractions(authManager);
	}
}
