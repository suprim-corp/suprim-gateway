package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.CompletionsRequest;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.model.ModelRouter;
import dev.suprim.gateway.proxy.ProxyFacade;
import dev.suprim.gateway.utils.ErrorResponse;
import dev.suprim.gateway.utils.RequestContext;
import dev.suprim.gateway.utils.TokenEstimator;
import dev.suprim.gateway.virtualkey.RateLimiter;
import dev.suprim.gateway.virtualkey.VirtualKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
class CompletionsController {

	private final ProxyFacade proxyFacade;
	private final ProviderDispatcher providerDispatcher;
	private final RateLimiter rateLimiter;
	private final TokenEstimator tokenEstimator;

	@PostMapping("/v1/chat/completions")
	void completions(
			@Valid @RequestBody CompletionsRequest request,
			HttpServletRequest httpReq, HttpServletResponse httpRes
	) throws Exception {
		VirtualKey key = RequestContext.resolveKey();
		String keyId = key != null ? key.id() : null;

		if (key != null && !rateLimiter.isAllowed(
				key.id(),
				key.rateLimitPerMin()
		)) {
			ErrorResponse.rateLimitOpenAi(httpRes);
			return;
		}

		String model = request.model();
		boolean stream = Boolean.TRUE.equals(request.stream());
		int inputTokens = tokenEstimator.estimateCompletionMessages(
				request.messages()
		) + tokenEstimator.estimateTools(request.tools());

		Provider provider = ModelRouter.resolveProvider(model);
		if (providerDispatcher.handles(provider)) {
			String actualModel = ModelRouter.stripPrefix(model);
			providerDispatcher.resolve(provider).handle(
					request, actualModel, stream, inputTokens, keyId,
					RequestContext.clientIp(httpReq), httpRes
			);
			return;
		}

		proxyFacade.handle(
				ProxyFacade.buildRequest(
						request,
						ProxyFacade.Format.OPENAI,
						stream,
						model,
						inputTokens,
						keyId,
						keyId,
						httpReq
				), httpRes
		);
	}
}
