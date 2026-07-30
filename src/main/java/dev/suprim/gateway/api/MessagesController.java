package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.logging.RequestLogCall;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@RestController
@Slf4j
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
		String keyId = Optional.ofNullable(key)
		                       .map(VirtualKey::id)
		                       .orElse(null);

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

		Map<String, Object> extra = request.additionalProperties();
		InternalRequest.Thinking thinking = parseThinking(
				extra == null ? null : extra.get("thinking")
		);
		if (thinking != null) {
			log.info(
					"[Messages] model={} thinking={}",
					actualModel,
					thinking.type()
			);
		}

		String clientIp = RequestContext.clientIp(httpReq);
		InternalRequest internalRequest =
				InternalRequest.builder()
				               .model(actualModel)
				               .messages(openAiMessages)
				               .stream(request.stream())
				               .tools(tools)
				               .temperature(request.temperature())
				               .topP(request.topP())
				               .maxTokens(request.maxTokens())
				               .thinking(thinking)
				               .clientSessionId(
						               RequestContext.clientSessionId(
								               httpReq
						               )
				               )
				               .build();
		providerDispatcher.dispatch(
				provider,
				internalRequest,
				RequestLogCall.start(
						actualModel,
						request.stream(),
						inputTokens,
						keyId,
						clientIp,
						Format.ANTHROPIC
				),
				httpRes
		);
	}

	private static InternalRequest.Thinking parseThinking(Object value) {
		if (!(value instanceof Map<?, ?> thinking)) {
			return null;
		}
		Object type = thinking.get("type");
		if (!(type instanceof String typeName) || typeName.isBlank()) {
			return null;
		}
		Object budget = thinking.get("budget_tokens");
		Integer budgetTokens = budget instanceof Number number
				? number.intValue()
				: null;
		return InternalRequest.Thinking.builder()
		                               .type(typeName.trim().toLowerCase())
		                               .budgetTokens(budgetTokens)
		                               .build();
	}

	@PostMapping("/v1/messages/count_tokens")
	Map<String, Object> countTokens(@RequestBody MessagesRequest request) {
		List<Message> messages = MessageConverter.fromAnthropic(request);
		List<Tool> tools = ToolMapper.fromAnthropic(request.tools());
		int inputTokens = tokenEstimator.estimateRequest(messages, tools);
		return Map.of("input_tokens", inputTokens);
	}
}
