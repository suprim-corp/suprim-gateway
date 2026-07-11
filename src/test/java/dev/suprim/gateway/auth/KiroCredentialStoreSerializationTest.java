package dev.suprim.gateway.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KiroCredentialStoreSerializationTest {

	@TempDir
	Path tempDir;

	@Test
	void load_antigravityAccount_readsProviderAndProjectId() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		String json = """
				{
				  "accounts": [
				    {
				      "provider": "antigravity",
				      "client_id": "1071006060591-xxx.apps.googleusercontent.com",
				      "client_secret": "GOCSPX-xxx",
				      "access_token": "ya29.test",
				      "refresh_token": "1//test",
				      "expires_at": "2026-07-11T12:00:00Z",
				      "project_id": "projects/cloudaicompanion-abc"
				    }
				  ]
				}
				""";
		Files.writeString(storePath, json);

		KiroCredentialStore testStore = new KiroCredentialStore(storePath);
		List<StoredAccount> accounts = testStore.load();

		assertEquals(1, accounts.size());
		StoredAccount acc = accounts.getFirst();
		assertEquals("antigravity", acc.provider());
		assertEquals("projects/cloudaicompanion-abc", acc.projectId());
		assertEquals("ya29.test", acc.accessToken());
	}

	@Test
	void load_legacyAccount_providerIsNull() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		String json = """
				{
				  "accounts": [
				    {
				      "profile_arn": "arn:aws:kiro::12345:profile/abc",
				      "auth_type": "KIRO_DESKTOP",
				      "access_token": "token123",
				      "refresh_token": "refresh123"
				    }
				  ]
				}
				""";
		Files.writeString(storePath, json);

		KiroCredentialStore testStore = new KiroCredentialStore(storePath);
		List<StoredAccount> accounts = testStore.load();

		assertEquals(1, accounts.size());
		assertNull(accounts.getFirst().provider());
		assertNull(accounts.getFirst().projectId());
	}

	@Test
	void save_antigravityAccount_writesProviderAndProjectId() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		KiroCredentialStore testStore = new KiroCredentialStore(storePath);

		StoredAccount acc = StoredAccount.builder()
		                                  .provider("antigravity")
		                                  .projectId("projects/test-123")
		                                  .clientId("client-id")
		                                  .clientSecret("client-secret")
		                                  .accessToken("ya29.abc")
		                                  .refreshToken("1//ref")
		                                  .expiresAt(Instant.parse("2026-07-11T12:00:00Z"))
		                                  .build();
		testStore.save(List.of(acc));

		String content = Files.readString(storePath);
		assertTrue(content.contains("\"provider\""));
		assertTrue(content.contains("antigravity"));
		assertTrue(content.contains("\"project_id\""));
		assertTrue(content.contains("projects/test-123"));
	}

	@Test
	void save_legacyAccount_omitsNullProviderAndProjectId() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		KiroCredentialStore testStore = new KiroCredentialStore(storePath);

		StoredAccount acc = StoredAccount.builder()
		                                  .profileArn("arn:aws:kiro::12345:profile/abc")
		                                  .authType("KIRO_DESKTOP")
		                                  .accessToken("token")
		                                  .refreshToken("refresh")
		                                  .build();
		testStore.save(List.of(acc));

		String content = Files.readString(storePath);
		assertFalse(content.contains("\"provider\""));
		assertFalse(content.contains("\"project_id\""));
	}

	@Test
	void findByProvider_returnsMatchingAccount() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		String json = """
				{
				  "accounts": [
				    {
				      "profile_arn": "arn:aws:kiro::12345:profile/abc",
				      "auth_type": "KIRO_DESKTOP",
				      "access_token": "kiro-token",
				      "refresh_token": "kiro-refresh"
				    },
				    {
				      "provider": "antigravity",
				      "client_id": "ag-client",
				      "access_token": "ag-token",
				      "refresh_token": "ag-refresh",
				      "project_id": "projects/xxx"
				    }
				  ]
				}
				""";
		Files.writeString(storePath, json);

		KiroCredentialStore testStore = new KiroCredentialStore(storePath);
		Optional<StoredAccount> result = testStore.findByProvider("antigravity");

		assertTrue(result.isPresent());
		assertEquals("ag-token", result.get().accessToken());
		assertEquals("projects/xxx", result.get().projectId());
	}

	@Test
	void findByProvider_returnsEmptyWhenNotFound() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		String json = """
				{
				  "accounts": [
				    {
				      "profile_arn": "arn:aws:kiro::12345:profile/abc",
				      "auth_type": "KIRO_DESKTOP",
				      "access_token": "token",
				      "refresh_token": "refresh"
				    }
				  ]
				}
				""";
		Files.writeString(storePath, json);

		KiroCredentialStore testStore = new KiroCredentialStore(storePath);
		Optional<StoredAccount> result = testStore.findByProvider("antigravity");

		assertTrue(result.isEmpty());
	}

	@Test
	void upsert_matchesByProviderAndClientId() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		String json = """
				{
				  "accounts": [
				    {
				      "provider": "antigravity",
				      "client_id": "ag-client",
				      "access_token": "old-token",
				      "refresh_token": "old-refresh"
				    }
				  ]
				}
				""";
		Files.writeString(storePath, json);

		KiroCredentialStore testStore = new KiroCredentialStore(storePath);
		StoredAccount updated = StoredAccount.builder()
		                                     .provider("antigravity")
		                                     .clientId("ag-client")
		                                     .accessToken("new-token")
		                                     .refreshToken("new-refresh")
		                                     .projectId("projects/new")
		                                     .build();
		testStore.upsert(updated);

		List<StoredAccount> accounts = testStore.load();
		assertEquals(1, accounts.size());
		assertEquals("new-token", accounts.getFirst().accessToken());
		assertEquals("projects/new", accounts.getFirst().projectId());
	}
}
