package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.ProxyChain;
import dev.suprim.gateway.proxy.StreamConverter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
class DeepSeekConfig {

	private static final String BASE_URL = "https://chat.deepseek.com";

	@Bean
	DeepSeekHttpClient deepSeekHttpClient(ProxyChain proxyChain) {
		return new DeepSeekHttpClient(proxyChain.currentEntry());
	}

	@Bean
	DeepSeekAuthManager deepSeekAuthManager(DeepSeekHttpClient httpClient) {
		return new DeepSeekAuthManager(httpClient, BASE_URL);
	}

	@Bean
	DeepSeekAccountPool deepSeekAccountPool(CredentialStore credentialStore) {
		List<StoredAccount> accounts = credentialStore.findAllByProvider(
				Provider.DEEPSEEK.name()
		);
		return new DeepSeekAccountPool(accounts, 2);
	}

	@Bean
	DeepSeekAutoContinue deepSeekAutoContinue(DeepSeekHttpClient httpClient) {
		return new DeepSeekAutoContinue(httpClient, BASE_URL);
	}

	@Bean
	DeepSeekFacade deepSeekFacade(
			DeepSeekHttpClient httpClient,
			DeepSeekAuthManager authManager,
			DeepSeekAccountPool accountPool,
			DeepSeekAutoContinue autoContinue,
			StreamConverter converter
	) {
		return new DeepSeekFacade(
				httpClient,
				authManager,
				accountPool,
				autoContinue,
				converter,
				BASE_URL
		);
	}
}
