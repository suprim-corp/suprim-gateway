package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.proxy.ProxyChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KiroAuthManagerMultiAccountTest {

	@TempDir
	Path tempDir;

	private KiroAuthManager authManager;

	@BeforeEach
	void setUp() {
		CredentialStore store = new CredentialStore(tempDir.resolve("creds.json"));
		AppConfig config = mock(AppConfig.class);
		when(config.profileArn()).thenReturn(null);
		when(config.region()).thenReturn("us-east-1");
		when(config.apiRegion()).thenReturn("us-east-1");
		ProxyChain proxyChain = mock(ProxyChain.class);
		authManager = new KiroAuthManager(config, store, proxyChain);
	}

	@Test
	void getAccessToken_apiKey_returnsDirectly() throws Exception {
		StoredAccount account = StoredAccount.builder()
		                                     .name("api-key-acc")
		                                     .provider("KIRO")
		                                     .authType("API_KEY")
		                                     .accessToken("my-api-key")
		                                     .build();

		String token = authManager.getAccessToken(account);

		assertEquals("my-api-key", token);
	}

	@Test
	void getAccessToken_sso_returnsTokenWhenNotExpired() throws Exception {
		StoredAccount account = StoredAccount.builder()
		                                     .name("sso-acc")
		                                     .provider("KIRO")
		                                     .authType("KIRO_DESKTOP")
		                                     .accessToken("valid-sso-token")
		                                     .refreshToken("refresh-1")
		                                     .expiresAt(Instant.now().plusSeconds(3600))
		                                     .build();

		String token = authManager.getAccessToken(account);

		assertEquals("valid-sso-token", token);
	}

	@Test
	void getAccessToken_cachesPerAccount() throws Exception {
		StoredAccount account1 = StoredAccount.builder()
		                                      .name("acc1")
		                                      .provider("KIRO")
		                                      .authType("API_KEY")
		                                      .accessToken("key-1")
		                                      .build();
		StoredAccount account2 = StoredAccount.builder()
		                                      .name("acc2")
		                                      .provider("KIRO")
		                                      .authType("API_KEY")
		                                      .accessToken("key-2")
		                                      .build();

		assertEquals("key-1", authManager.getAccessToken(account1));
		assertEquals("key-2", authManager.getAccessToken(account2));
		assertEquals("key-1", authManager.getAccessToken(account1));
	}
}
