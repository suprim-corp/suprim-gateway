package dev.suprim.gateway.api;

import dev.suprim.gateway.logging.ProviderOutcome;
import dev.suprim.gateway.logging.RequestLogCall;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.antigravity.AntigravityFacade;
import dev.suprim.gateway.provider.codex.CodexFacade;
import dev.suprim.gateway.provider.deepseek.DeepSeekFacade;
import dev.suprim.gateway.provider.xai.XaiFacade;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroFacade;
import dev.suprim.gateway.proxy.token.RequestOptimizer;
import dev.suprim.gateway.utils.TokenEstimator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class ProviderDispatcher {

	private final Map<Provider, ProviderHandler> handlers;
	private final RequestLogPublisher logPublisher;

	ProviderDispatcher(
			AntigravityFacade antigravityFacade,
			XaiFacade xaiFacade,
			CodexFacade codexFacade,
			KiroFacade kiroFacade,
			DeepSeekFacade deepSeekFacade,
			RequestOptimizer requestOptimizer,
			RequestLogPublisher logPublisher,
			TokenEstimator tokenEstimator
	) {
		this.logPublisher = logPublisher;
		handlers = Map.of(
				Provider.KIRO, optimized(
						Provider.KIRO,
						kiroFacade::handle,
						requestOptimizer,
						tokenEstimator
				),
				Provider.ANTIGRAVITY, optimized(
						Provider.ANTIGRAVITY,
						antigravityFacade::handle,
						requestOptimizer,
						tokenEstimator
				),
				Provider.GROK, xaiFacade::handle,
				Provider.XAI, xaiFacade::handle,
				Provider.CODEX, optimized(
						Provider.CODEX,
						codexFacade::handle,
						requestOptimizer,
						tokenEstimator
				),
				Provider.DEEPSEEK, deepSeekFacade::handle
		);
	}

	private static ProviderHandler optimized(
			Provider provider,
			ProviderHandler handler,
			RequestOptimizer requestOptimizer,
			TokenEstimator tokenEstimator
	) {
		return (request, call, httpRes) -> {
			InternalRequest optimized = requestOptimizer.optimize(
					provider,
					request
			).request();
			int inputTokens = tokenEstimator.estimateRequest(
					optimized.messages(), optimized.tools()
			);
			return handler.handle(
					optimized,
					call.withEstimatedInputTokens(inputTokens),
					httpRes
			);
		};
	}

	void dispatch(
			Provider provider,
			InternalRequest request,
			RequestLogCall call,
			HttpServletResponse httpRes
	) throws Exception {
		ProviderOutcome outcome =
				handlers.get(provider)
				        .handle(
						        request,
						        call,
						        httpRes
				        );
		if (outcome != null && outcome.event() != null) {
			logPublisher.publish(outcome.event());
		}
	}

	boolean handles(Provider provider) {
		return handlers.containsKey(provider);
	}
}
