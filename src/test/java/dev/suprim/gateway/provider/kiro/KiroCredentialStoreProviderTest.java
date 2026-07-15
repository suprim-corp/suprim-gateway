package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CredentialStoreProviderTest {

	@TempDir
	Path tempDir;

	@Test
	void providerEnum_hasExpectedValues() {
		assertEquals(6, Provider.values().length);
		assertNotNull(Provider.valueOf("KIRO"));
		assertNotNull(Provider.valueOf("ANTIGRAVITY"));
		assertNotNull(Provider.valueOf("GROK"));
		assertNotNull(Provider.valueOf("XAI"));
		assertNotNull(Provider.valueOf("CODEX"));
		assertNotNull(Provider.valueOf("DEEPSEEK"));
	}

	@Test
	void storedAccount_carriesProviderAndProjectId() {
		StoredAccount account = StoredAccount.builder()
		                                     .provider("antigravity")
		                                     .projectId("projects/cloudaicompanion-abc123")
		                                     .clientId("test-client")
		                                     .accessToken("ya29.xxx")
		                                     .refreshToken("1//xxx")
		                                     .expiresAt(Instant.now().plusSeconds(3600))
		                                     .build();

		assertEquals("antigravity", account.provider());
		assertEquals("projects/cloudaicompanion-abc123", account.projectId());
	}

	@Test
	void storedAccount_withTokens_carriesProviderAndProjectId() {
		StoredAccount original = StoredAccount.builder()
		                                      .provider("antigravity")
		                                      .projectId("projects/xxx")
		                                      .clientId("cid")
		                                      .accessToken("old")
		                                      .refreshToken("ref")
		                                      .expiresAt(Instant.now())
		                                      .build();

		Instant newExpiry = Instant.now().plusSeconds(7200);
		StoredAccount updated = original.withTokens("new-token", "new-refresh", newExpiry);

		assertEquals("antigravity", updated.provider());
		assertEquals("projects/xxx", updated.projectId());
		assertEquals("new-token", updated.accessToken());
	}

	@Test
	void legacyAccount_hasNullProvider() {
		StoredAccount legacy = StoredAccount.builder()
		                                    .profileArn("arn:aws:kiro::12345:profile/abc")
		                                    .authType("KIRO_DESKTOP")
		                                    .accessToken("token")
		                                    .refreshToken("refresh")
		                                    .build();

		assertNull(legacy.provider());
		assertNull(legacy.projectId());
	}
}
