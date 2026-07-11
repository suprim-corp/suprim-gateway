package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.ResponsesRequest;
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

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
class ResponsesController {

	private final ProviderDispatcher providerDispatcher;
	private final RateLimiter rateLimiter;
	private final TokenEstimator tokenEstimator;

	@PostMapping("/v1/responses")
	void responses(
			@Valid @RequestBody ResponsesRequest request,
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

		List<Message> messages = new ArrayList<>(
				MessageConverter.fromResponses(request.input())
		);

		String instructions = request.instructions();
		if (instructions != null && !instructions.isBlank()) {
			messages.addFirst(Message.of("system", instructions));
		}

		log.debug("[Responses] messages count={}, roles={}", messages.size(),
				messages.stream().map(Message::role).toList());
		for (int i = 0; i < messages.size(); i++) {
			Message msg = messages.get(i);
			String content = msg.content() != null ? msg.content().toString() : "null";
			log.debug("[Responses] msg[{}] role={} content={}",
					i, msg.role(), content.length() > 100 ? content.substring(0, 100) + "..." : content);
		}

		List<Tool> tools = ToolMapper.fromResponses(request.tools());
		int inputTokens = tokenEstimator.estimateRequest(
				messages,
				tools
		);

		Provider provider = ModelRouter.resolveProvider(request.model());
		String actualModel = ModelRouter.stripPrefix(request.model());

		InternalRequest openAiReq =
				InternalRequest.builder()
				               .model(actualModel)
				               .messages(messages)
				               .stream(stream)
				               .tools(tools)
				               .temperature(request.temperature())
				               .maxTokens(request.maxOutputTokens())
				               .build();

		providerDispatcher.resolve(provider)
		                  .handle(
				                  openAiReq,
				                  actualModel,
				                  stream,
				                  inputTokens,
				                  keyId,
				                  RequestContext.clientIp(httpReq),
				                  Format.RESPONSES,
				                  httpRes
		                  );
	}
}
