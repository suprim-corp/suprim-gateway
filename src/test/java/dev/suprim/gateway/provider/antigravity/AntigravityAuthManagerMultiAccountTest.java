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
		StoredAccount account = StoredAccount.builder()
		                                     .name("acc1")
		                                     .provider("ANTIGRAVITY")
		                                     .accessToken("valid-token")
		                                     .refreshToken("refresh-1")
		                                     .projectId("proj-1")
		                                     .expiresAt(Instant.now().plusSeconds(3600))
		                                     .build();

		String token = authManager.getAccessToken(account);

		assertEquals("valid-token", token);
	}

	@Test
	void getAccessToken_cachesPerAccount() {
		StoredAccount account1 = StoredAccount.builder()
		                                      .name("acc1")
		                                      .provider("ANTIGRAVITY")
		                                      .accessToken("token-1")
		                                      .refreshToken("refresh-1")
		                                      .projectId("proj-1")
		                                      .expiresAt(Instant.now().plusSeconds(3600))
		                                      .build();
		StoredAccount account2 = StoredAccount.builder()
		                                      .name("acc2")
		                                      .provider("ANTIGRAVITY")
		                                      .accessToken("token-2")
		                                      .refreshToken("refresh-2")
		                                      .projectId("proj-2")
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
		StoredAccount account = StoredAccount.builder()
		                                     .name("acc1")
		                                     .provider("ANTIGRAVITY")
		                                     .accessToken("token")
		                                     .refreshToken("refresh")
		                                     .projectId("my-project-id")
		                                     .expiresAt(Instant.now().plusSeconds(3600))
		                                     .build();

		String projectId = authManager.getProjectId(account);

		assertEquals("my-project-id", projectId);
	}
}
