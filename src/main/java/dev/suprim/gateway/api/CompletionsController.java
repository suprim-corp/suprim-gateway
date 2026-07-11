package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.CompletionsRequest;
import dev.suprim.gateway.api.request.RequestMapper;
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

import java.util.List;
import java.util.Map;

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
		List<Map<String, Object>> messages = RequestMapper.messagesToList(
				request.messages()
		);
		List<Map<String, Object>> tools = RequestMapper.toolsToList(
				request.tools()
		);
		int inputTokens = tokenEstimator.estimateRequest(messages, tools);

		Map<String, Object> rawRequest = RequestMapper.toMap(request);

		Provider provider = ModelRouter.resolveProvider(model);
		if (providerDispatcher.handles(provider)) {
			String actualModel = ModelRouter.stripPrefix(model);
			rawRequest.put("model", actualModel);
			providerDispatcher.resolve(provider).handle(
					rawRequest, actualModel, stream, inputTokens, keyId,
					RequestContext.clientIp(httpReq), httpRes
			);
			return;
		}

		proxyFacade.handle(
				ProxyFacade.buildRequest(
						rawRequest,
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
