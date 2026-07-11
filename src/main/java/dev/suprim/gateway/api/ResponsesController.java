package dev.suprim.gateway.api;

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

	@SuppressWarnings("unchecked")
	@PostMapping("/v1/responses")
	void responses(
			@RequestBody Map<String, Object> request,
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

		String model = (String) request.getOrDefault(
				"model",
				"claude-sonnet-4-5"
		);
		Object input = request.get("input");
		boolean stream = Boolean.TRUE.equals(request.get("stream"));

		if (input == null) {
			ErrorResponse.badRequest(httpRes, "model and input are required");
			return;
		}

		List<Map<String, Object>> messages = new ArrayList<>(
				payloadBuilder.convertResponsesInput(input)
		);

		Object instructions = request.get("instructions");
		if (instructions instanceof String instr && !instr.isBlank()) {
			messages.addFirst(Map.of("role", "system", "content", instr));
		}

		List<Map<String, Object>> tools = (List<Map<String, Object>>) request.get(
				"tools");
		int inputTokens = tokenEstimator.estimateRequest(messages, tools);

		HashMap<String, Object> openAiReq = new HashMap<>();
		openAiReq.put("messages", messages);
		openAiReq.put("stream", stream);
		if (tools != null) openAiReq.put("tools", tools);
		if (request.containsKey("temperature")) openAiReq.put(
				"temperature",
				request.get("temperature")
		);
		if (request.containsKey("max_output_tokens"))
			openAiReq.put("max_tokens", request.get("max_output_tokens"));

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
