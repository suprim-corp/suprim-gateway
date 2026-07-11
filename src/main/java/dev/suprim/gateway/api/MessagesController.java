package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.model.ModelRouter;
import dev.suprim.gateway.proxy.*;
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
class MessagesController {

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

		List<Message> openAiMessages = MessageConverter.fromAnthropic(request);
		List<Tool> tools = ToolMapper.fromAnthropic(request.tools());
		int inputTokens = tokenEstimator.estimateRequest(
				openAiMessages,
				tools
		);

		Provider provider = ModelRouter.resolveProvider(request.model());
		String actualModel = ModelRouter.stripPrefix(request.model());

		InternalRequest openAiReq =
				InternalRequest.builder()
				               .model(actualModel)
				               .messages(openAiMessages)
				               .stream(request.stream())
				               .tools(tools)
				               .build();

		providerDispatcher.resolve(provider)
		                  .handle(
				                  openAiReq,
				                  actualModel,
				                  request.stream(),
				                  inputTokens,
				                  keyId,
				                  RequestContext.clientIp(httpReq),
				                  Format.ANTHROPIC,
				                  httpRes
		                  );
	}

	@PostMapping("/v1/messages/count_tokens")
	Map<String, Object> countTokens(@RequestBody MessagesRequest request) {
		List<Message> messages = MessageConverter.fromAnthropic(request);
		List<Tool> tools = ToolMapper.fromAnthropic(request.tools());
		int inputTokens = tokenEstimator.estimateRequest(messages, tools);
		return Map.of("input_tokens", inputTokens);
	}
}
