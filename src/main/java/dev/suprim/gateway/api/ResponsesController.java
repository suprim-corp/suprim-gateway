package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.RequestMapper;
import dev.suprim.gateway.api.request.ResponsesRequest;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.model.ModelRouter;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
class ResponsesController {

	private final ProxyFacade proxyFacade;
	private final ProviderDispatcher providerDispatcher;
	private final PayloadBuilder payloadBuilder;
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

		String model = request.model();
		boolean stream = Boolean.TRUE.equals(request.stream());

		List<Map<String, Object>> messages = new ArrayList<>(
				payloadBuilder.convertResponsesInput(request.input())
		);

		String instructions = request.instructions();
		if (instructions != null && !instructions.isBlank()) {
			messages.addFirst(
					Map.of(
							"role",
							"system",
							"content",
							instructions
					)
			);
		}

		List<Map<String, Object>> tools = RequestMapper.toolsToList(
				request.tools()
		);
		int inputTokens = tokenEstimator.estimateRequest(messages, tools);

		HashMap<String, Object> openAiReq = new HashMap<>();
		openAiReq.put("messages", messages);
		openAiReq.put("stream", stream);
		if (tools != null) openAiReq.put("tools", tools);
		if (request.temperature() != null) openAiReq.put(
				"temperature",
				request.temperature()
		);
		if (request.maxOutputTokens() != null)
			openAiReq.put("max_tokens", request.maxOutputTokens());

		Provider provider = ModelRouter.resolveProvider(model);
		String actualModel = ModelRouter.stripPrefix(model);
		openAiReq.put("model", actualModel);

		if (providerDispatcher.handles(provider)) {
			providerDispatcher.resolve(provider).handle(
					openAiReq, actualModel, stream, inputTokens, keyId,
					RequestContext.clientIp(httpReq), httpRes
			);
			return;
		}

		proxyFacade.handle(
				ProxyFacade.buildRequest(
						openAiReq,
						ProxyFacade.Format.RESPONSES,
						stream,
						actualModel,
						inputTokens,
						keyId,
						keyId,
						httpReq
				), httpRes
		);
	}
}
