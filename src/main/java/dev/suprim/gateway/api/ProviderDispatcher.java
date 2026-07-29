package dev.suprim.gateway.api;

import dev.suprim.gateway.provider.antigravity.AntigravityFacade;
import dev.suprim.gateway.provider.codex.CodexFacade;
import dev.suprim.gateway.provider.deepseek.DeepSeekFacade;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.xai.XaiFacade;
import dev.suprim.gateway.proxy.kiro.KiroFacade;
import dev.suprim.gateway.proxy.token.RequestOptimizer;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class ProviderDispatcher {

	private final Map<Provider, ProviderHandler> handlers;

	ProviderDispatcher(
			AntigravityFacade antigravityFacade,
			XaiFacade xaiFacade,
			CodexFacade codexFacade,
			KiroFacade kiroFacade,
			DeepSeekFacade deepSeekFacade,
			RequestOptimizer requestOptimizer
	) {
		handlers = Map.of(
				Provider.KIRO, optimized(Provider.KIRO, kiroFacade::handle, requestOptimizer),
				Provider.ANTIGRAVITY, optimized(Provider.ANTIGRAVITY, antigravityFacade::handle, requestOptimizer),
				Provider.GROK, xaiFacade::handle,
				Provider.XAI, xaiFacade::handle,
				Provider.CODEX, optimized(Provider.CODEX, codexFacade::handle, requestOptimizer),
				Provider.DEEPSEEK, deepSeekFacade::handle
		);
	}

	private static ProviderHandler optimized(
			Provider provider,
			ProviderHandler handler,
			RequestOptimizer requestOptimizer
	) {
		return (request, model, stream, inputTokens, keyId, clientIp, format, httpRes) ->
				handler.handle(
						requestOptimizer.optimize(provider, request).request(), model, stream,
						inputTokens, keyId, clientIp, format, httpRes
				);
	}

	ProviderHandler resolve(Provider provider) {
		return handlers.get(provider);
	}

	boolean handles(Provider provider) {
		return handlers.containsKey(provider);
	}
}
