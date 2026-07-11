package dev.suprim.gateway.antigravity;

import dev.suprim.gateway.auth.KiroCredentialStore;
import dev.suprim.gateway.auth.StoredAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityAuthManagerTest {

	@TempDir
	Path tempDir;

	private KiroCredentialStore store;
	private AntigravityAuthManager authManager;

	@BeforeEach
	void setUp() {
		Path storePath = tempDir.resolve("credentials.json");
		store = new KiroCredentialStore(storePath);
		authManager = new AntigravityAuthManager(store);
	}

	@Test
	void isConnected_falseWhenNoAntigravityAccount() {
		assertFalse(authManager.isConnected());
	}

	@Test
	void isConnected_trueWhenAntigravityAccountExists() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		String json = """
				{
				  "accounts": [
				    {
				      "provider": "ANTIGRAVITY",
				      "client_id": "1071006060591-xxx.apps.googleusercontent.com",
				      "access_token": "ya29.valid",
				      "refresh_token": "1//refresh",
				      "expires_at": "%s",
				      "project_id": "projects/test"
				    }
				  ]
				}
				""".formatted(Instant.now().plusSeconds(3600).toString());
		Files.writeString(storePath, json);

		AntigravityAuthManager mgr = new AntigravityAuthManager(store);
		mgr.init();
		assertTrue(mgr.isConnected());
	}

	@Test
	void getAccessToken_throwsWhenNotConnected() {
		assertThrows(IllegalStateException.class, () -> authManager.getAccessToken());
	}

	@Test
	void getAccessToken_returnsTokenWhenValid() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		Instant future = Instant.now().plusSeconds(3600);
		String json = """
				{
				  "accounts": [
				    {
				      "provider": "ANTIGRAVITY",
				      "client_id": "1071006060591-xxx.apps.googleusercontent.com",
				      "access_token": "ya29.valid-token",
				      "refresh_token": "1//refresh",
				      "expires_at": "%s",
				      "project_id": "projects/test"
				    }
				  ]
				}
				""".formatted(future.toString());
		Files.writeString(storePath, json);

		AntigravityAuthManager mgr = new AntigravityAuthManager(store);
		mgr.init();
		assertEquals("ya29.valid-token", mgr.getAccessToken());
	}

	@Test
	void getProjectId_returnsStoredProjectId() throws Exception {
		Path storePath = tempDir.resolve("credentials.json");
		Instant future = Instant.now().plusSeconds(3600);
		String json = """
				{
				  "accounts": [
				    {
				      "provider": "ANTIGRAVITY",
				      "client_id": "cid",
				      "access_token": "ya29.x",
				      "refresh_token": "1//r",
				      "expires_at": "%s",
				      "project_id": "projects/cloudaicompanion-abc123"
				    }
				  ]
				}
				""".formatted(future.toString());
		Files.writeString(storePath, json);

		AntigravityAuthManager mgr = new AntigravityAuthManager(store);
		mgr.init();
		assertEquals("projects/cloudaicompanion-abc123", mgr.getProjectId());
	}

	@Test
	void getProjectId_throwsWhenNotConnected() {
		assertThrows(IllegalStateException.class, () -> authManager.getProjectId());
	}

	@Test
	void saveCredentials_persistsToStore() {
		Instant expiry = Instant.now().plusSeconds(3600);
		authManager.saveCredentials("ya29.new", "1//new-refresh", expiry, "projects/new");

		assertTrue(authManager.isConnected());
		assertEquals("ya29.new", authManager.getAccessToken());
		assertEquals("projects/new", authManager.getProjectId());

		// Verify persisted
		StoredAccount persisted = store.findByProvider("ANTIGRAVITY").orElse(null);
		assertNotNull(persisted);
		assertEquals("ya29.new", persisted.accessToken());
		assertEquals("projects/new", persisted.projectId());
	}
}
