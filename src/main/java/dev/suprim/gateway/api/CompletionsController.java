package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.CompletionsRequest;
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

@RequiredArgsConstructor
@RestController
class CompletionsController {

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

		boolean stream = Boolean.TRUE.equals(request.stream());
		List<Tool> tools = ToolMapper.fromCompletions(request.tools());
		int inputTokens = tokenEstimator.estimateCompletionMessages(
				request.messages()
		) + tokenEstimator.estimateTools(tools);

		Provider provider = ModelRouter.resolveProvider(request.model());
		String actualModel = ModelRouter.stripPrefix(request.model());

		List<Message> messages =
				request.messages()
				       .stream()
				       .map(m -> Message.builder()
				                        .role(m.role())
				                        .content(m.content())
				                        .toolCalls(
						                        m.toolCalls() ==
						                        null ? null : m.toolCalls()
						                                       .stream()
						                                       .map(tc -> Message.ToolCall.builder()
						                                                                  .id(tc.id())
						                                                                  .type(tc.type())
						                                                                  .function(
								                                                                  tc.function() ==
								                                                                  null ? null :
										                                                                  Message.Function.builder()
										                                                                                  .name(
												                                                                                  tc.function()
												                                                                                    .name()
										                                                                                  )
										                                                                                  .arguments(
												                                                                                  tc.function()
												                                                                                    .arguments()
										                                                                                  )
										                                                                                  .build()
						                                                                  )
						                                                                  .build())
						                                       .toList())
				                        .toolCallId(m.toolCallId())
				                        .build())
				       .toList();

		InternalRequest internalReq =
				InternalRequest.builder()
				               .model(actualModel)
				               .messages(messages)
				               .stream(stream)
				               .tools(tools)
				               .temperature(request.temperature())
				               .maxTokens(request.maxTokens())
				               .build();

		providerDispatcher.resolve(provider).handle(
				internalReq, actualModel, stream, inputTokens, keyId,
				RequestContext.clientIp(httpReq), Format.COMPLETION,
				httpRes
		);
	}
}
