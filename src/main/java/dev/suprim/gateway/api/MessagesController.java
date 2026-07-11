package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.model.ModelRouter;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.PayloadBuilder;
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

@RequiredArgsConstructor
@RestController
class MessagesController {

	private final ProxyFacade proxyFacade;
	private final PayloadBuilder payloadBuilder;
	private final ProviderDispatcher providerDispatcher;
	private final RateLimiter rateLimiter;
	private final TokenEstimator tokenEstimator;

	@PostMapping("/v1/messages")
	void messages(
			@Valid @RequestBody MessagesRequest request,
			HttpServletRequest httpReq, HttpServletResponse httpRes
	) throws Exception {
		VirtualKey key = RequestContext.resolveKey();
		String keyId = key != null ? key.id() : null;

		if (key != null && !rateLimiter.isAllowed(
				key.id(),
				key.rateLimitPerMin()
		)) {
			ErrorResponse.rateLimitAnthropic(httpRes);
			return;
		}

		List<Message> openAiMessages = payloadBuilder.convertAnthropicMessages(
				request
		);
		int inputTokens = tokenEstimator.estimateRequest(
				openAiMessages,
				request.tools()
		);

		Provider provider = ModelRouter.resolveProvider(request.model());
		String actualModel = ModelRouter.stripPrefix(request.model());

		InternalRequest openAiReq =
				InternalRequest.builder()
				               .model(actualModel)
				               .messages(openAiMessages)
				               .stream(request.stream())
				               .tools(request.tools())
				               .build();

		if (providerDispatcher.handles(provider)) {
			providerDispatcher.resolve(provider)
			                  .handle(
					                  openAiReq,
					                  actualModel,
					                  request.stream(),
					                  inputTokens,
					                  keyId,
					                  RequestContext.clientIp(httpReq),
					                  httpRes
			                  );
			return;
		}

		proxyFacade.handle(
				ProxyFacade.buildRequest(
						openAiReq,
						ProxyFacade.Format.ANTHROPIC,
						request.stream(),
						actualModel,
						inputTokens,
						keyId,
						keyId,
						httpReq
				),
				httpRes
		);
	}
}
