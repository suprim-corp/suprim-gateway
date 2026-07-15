package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.ProxyChain;
import dev.suprim.gateway.proxy.StreamConverter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekConfigTest {

	@Test
	void beansAreCreatedCorrectly() {
		DeepSeekConfig config = new DeepSeekConfig();

		ProxyChain proxyChain = mock(ProxyChain.class);
		when(proxyChain.currentEntry()).thenReturn(null);

		DeepSeekHttpClient httpClient = config.deepSeekHttpClient(proxyChain);
		assertNotNull(httpClient);

		DeepSeekAuthManager authManager = config.deepSeekAuthManager(httpClient);
		assertNotNull(authManager);

		CredentialStore credentialStore = mock(CredentialStore.class);
		when(credentialStore.findAllByProvider(Provider.DEEPSEEK.name())).thenReturn(List.of(
				StoredAccount.builder().name("a@b.com").provider("DEEPSEEK").build()
		));
		DeepSeekAccountPool pool = config.deepSeekAccountPool(credentialStore);
		assertNotNull(pool);

		DeepSeekAutoContinue autoContinue = config.deepSeekAutoContinue(httpClient);
		assertNotNull(autoContinue);

		DeepSeekFacade facade = config.deepSeekFacade(httpClient, authManager, pool, autoContinue, new StreamConverter());
		assertNotNull(facade);
	}
}
