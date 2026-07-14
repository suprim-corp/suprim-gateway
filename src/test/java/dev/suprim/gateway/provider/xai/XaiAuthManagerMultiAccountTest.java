package dev.suprim.gateway.provider.xai;

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

class XaiAuthManagerMultiAccountTest {

	@TempDir
	Path tempDir;

	private XaiAuthManager authManager;

	@BeforeEach
	void setUp() {
		CredentialStore store = new CredentialStore(tempDir.resolve("creds.json"));
		authManager = new XaiAuthManager(store);
	}

	@Test
	void getAccessToken_returnsTokenWhenNotExpired() {
		StoredAccount account = StoredAccount.builder()
		                                     .name("account1")
		                                     .provider("XAI")
		                                     .accessToken("valid-token")
		                                     .refreshToken("refresh-1")
		                                     .expiresAt(Instant.now().plusSeconds(3600))
		                                     .build();

		String token = authManager.getAccessToken(account);

		assertEquals("valid-token", token);
	}

	@Test
	void getAccessToken_cachesPerAccount() {
		StoredAccount account1 = StoredAccount.builder()
		                                      .name("acc1")
		                                      .provider("XAI")
		                                      .accessToken("token-1")
		                                      .refreshToken("refresh-1")
		                                      .expiresAt(Instant.now().plusSeconds(3600))
		                                      .build();
		StoredAccount account2 = StoredAccount.builder()
		                                      .name("acc2")
		                                      .provider("XAI")
		                                      .accessToken("token-2")
		                                      .refreshToken("refresh-2")
		                                      .expiresAt(Instant.now().plusSeconds(3600))
		                                      .build();

		assertEquals("token-1", authManager.getAccessToken(account1));
		assertEquals("token-2", authManager.getAccessToken(account2));
		assertEquals("token-1", authManager.getAccessToken(account1));
	}

	@Test
	void getAccessToken_refreshesWhenExpired() {
		StoredAccount account = StoredAccount.builder()
		                                     .name("expired-acc")
		                                     .provider("XAI")
		                                     .accessToken("old-token")
		                                     .refreshToken("refresh-tok")
		                                     .expiresAt(Instant.now().minusSeconds(100))
		                                     .build();

		XaiTokenResponse mockResponse = XaiTokenResponse.builder()
		                                                 .accessToken("new-token")
		                                                 .refreshToken("new-refresh")
		                                                 .expiresIn(3600)
		                                                 .build();

		try (MockedStatic<XaiTokenRefresher> mocked = mockStatic(XaiTokenRefresher.class)) {
			mocked.when(() -> XaiTokenRefresher.refresh("refresh-tok"))
			      .thenReturn(mockResponse);

			String token = authManager.getAccessToken(account);

			assertEquals("new-token", token);
			mocked.verify(() -> XaiTokenRefresher.refresh("refresh-tok"));
		}
	}

	@Test
	void getAccessToken_throwsWhenNoRefreshToken() {
		StoredAccount account = StoredAccount.builder()
		                                     .name("no-refresh")
		                                     .provider("XAI")
		                                     .accessToken("old-token")
		                                     .expiresAt(Instant.now().minusSeconds(100))
		                                     .build();

		assertThrows(RuntimeException.class, () -> authManager.getAccessToken(account));
	}
}
