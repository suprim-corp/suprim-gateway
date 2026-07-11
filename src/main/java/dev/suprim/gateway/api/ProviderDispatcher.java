package dev.suprim.gateway.api;

import dev.suprim.gateway.antigravity.AntigravityFacade;
import dev.suprim.gateway.auth.Provider;
import dev.suprim.gateway.xai.XaiFacade;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class ProviderDispatcher {

	private final Map<Provider, ProviderHandler> handlers;

	ProviderDispatcher(
			AntigravityFacade antigravityFacade,
			XaiFacade xaiFacade
	) {
		handlers = Map.of(
				Provider.ANTIGRAVITY, antigravityFacade::handle,
				Provider.GROK, xaiFacade::handle,
				Provider.XAI, xaiFacade::handle
		);
	}

	ProviderHandler resolve(Provider provider) {
		return handlers.get(provider);
	}

	boolean handles(Provider provider) {
		return handlers.containsKey(provider);
	}
}
