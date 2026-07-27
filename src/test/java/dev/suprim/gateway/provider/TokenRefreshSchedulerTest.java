package dev.suprim.gateway.provider;

import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.antigravity.GoogleTokenRefresher;
import dev.suprim.gateway.provider.antigravity.GoogleTokenResponse;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import dev.suprim.gateway.proxy.ProxyChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TokenRefreshSchedulerTest {

	@Test
	void refreshAll_evictsAntigravityTokenCacheAfterPersistingRefreshedAccount() {
		CredentialStore credentialStore = mock(CredentialStore.class);
		ProxyChain proxyChain = mock(ProxyChain.class);
		XaiAuthManager xaiAuthManager = mock(XaiAuthManager.class);
		AntigravityAuthManager antigravityAuthManager = mock(AntigravityAuthManager.class);
		TokenRefreshScheduler scheduler = new TokenRefreshScheduler(
				credentialStore, proxyChain, xaiAuthManager, antigravityAuthManager
		);
		StoredAccount account = StoredAccount.builder()
		                                     .name("account@example.com")
		                                     .provider(Provider.ANTIGRAVITY.name())
		                                     .accessToken("old-access")
		                                     .refreshToken("old-refresh")
		                                     .projectId("project")
		                                     .expiresAt(Instant.now().minusSeconds(1))
		                                     .build();
		when(credentialStore.load()).thenReturn(List.of(account));

		try (MockedStatic<GoogleTokenRefresher> refresher = mockStatic(GoogleTokenRefresher.class)) {
			refresher.when(() -> GoogleTokenRefresher.refresh(
					anyString(), anyString(), anyString()
			)).thenReturn(new GoogleTokenResponse("new-access", "new-refresh", 3600));

			scheduler.refreshAll();
		}

		ArgumentCaptor<StoredAccount> persisted = ArgumentCaptor.forClass(StoredAccount.class);
		verify(credentialStore).upsert(persisted.capture());
		assertEquals(account.name(), persisted.getValue().name());
		assertEquals(account.provider(), persisted.getValue().provider());
		assertEquals("new-access", persisted.getValue().accessToken());
		verify(antigravityAuthManager).evictTokenCache(account);
		verifyNoInteractions(xaiAuthManager);
	}
}
