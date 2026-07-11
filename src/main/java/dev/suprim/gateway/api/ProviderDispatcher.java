package dev.suprim.gateway.api;

import dev.suprim.gateway.provider.antigravity.AntigravityFacade;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.xai.XaiFacade;
import dev.suprim.gateway.proxy.KiroFacade;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class ProviderDispatcher {

	private final Map<Provider, ProviderHandler> handlers;

	ProviderDispatcher(
			AntigravityFacade antigravityFacade,
			XaiFacade xaiFacade,
			KiroFacade kiroFacade
	) {
		handlers = Map.of(
				Provider.KIRO, kiroFacade::handle,
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
