package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AntigravityAuthManagerMultiAccountTest {

	@TempDir
	Path tempDir;

	private AntigravityAuthManager authManager;

	@BeforeEach
	void setUp() {
		CredentialStore store = new CredentialStore(tempDir.resolve("creds.json"));
		authManager = new AntigravityAuthManager(store, null);
	}

	@Test
	void getAccessToken_returnsTokenWhenNotExpired() {
		StoredAccount account = account("acc1", null, "valid-token");

		String token = authManager.getAccessToken(account);

		assertEquals("valid-token", token);
	}

	@Test
	void getAccessToken_cachesPerAccount() {
		StoredAccount account1 = account("acc1", null, "token-1");
		StoredAccount account2 = account("acc2", null, "token-2");

		assertEquals("token-1", authManager.getAccessToken(account1));
		assertEquals("token-2", authManager.getAccessToken(account2));
		assertEquals("token-1", authManager.getAccessToken(account1));
	}

	@Test
	void getAccessToken_returnsDistinctTokensForAccountsWithoutCacheIdentity() {
		StoredAccount account1 = account(null, null, "token-1");
		StoredAccount account2 = account(null, null, "token-2");

		assertEquals("token-1", authManager.getAccessToken(account1));
		assertEquals("token-2", authManager.getAccessToken(account2));
	}

	@Test
	void evictTokenCache_usesNewTokenForIdentifiedAccount() {
		StoredAccount oldAccount = account("acc1", null, "old-token");
		StoredAccount refreshedAccount = account("acc1", null, "new-token");

		assertEquals("old-token", authManager.getAccessToken(oldAccount));
		authManager.evictTokenCache(oldAccount);

		assertEquals("new-token", authManager.getAccessToken(refreshedAccount));
	}

	@Test
	void getAccessToken_refreshesWhenExpired() {
		StoredAccount account = StoredAccount.builder()
		                                     .name("expired-acc")
		                                     .provider("ANTIGRAVITY")
		                                     .accessToken("old-token")
		                                     .refreshToken("refresh-tok")
		                                     .projectId("proj-1")
		                                     .expiresAt(Instant.now().minusSeconds(100))
		                                     .build();

		GoogleTokenResponse mockResponse = new GoogleTokenResponse(
				"new-token", "new-refresh", 3600
		);

		try (MockedStatic<GoogleTokenRefresher> mocked = mockStatic(GoogleTokenRefresher.class)) {
			mocked.when(() -> GoogleTokenRefresher.refresh(eq("refresh-tok"), anyString(), anyString()))
			      .thenReturn(mockResponse);

			String token = authManager.getAccessToken(account);

			assertEquals("new-token", token);
		}
	}

	@Test
	void getProjectId_returnsAccountProjectId() {
		StoredAccount account = account("acc1", null, "token");

		String projectId = authManager.getProjectId(account);

		assertEquals("proj-1", projectId);
	}

	private StoredAccount account(String name, String clientId, String accessToken) {
		return StoredAccount.builder()
		                    .name(name)
		                    .clientId(clientId)
		                    .provider("ANTIGRAVITY")
		                    .accessToken(accessToken)
		                    .refreshToken("refresh")
		                    .projectId("proj-1")
		                    .expiresAt(Instant.now().plusSeconds(3600))
		                    .build();
	}
}
