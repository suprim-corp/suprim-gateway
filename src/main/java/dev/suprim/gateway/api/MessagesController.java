package dev.suprim.gateway.api;

import dev.suprim.gateway.auth.Provider;
import dev.suprim.gateway.model.ModelRouter;
import dev.suprim.gateway.proxy.ContentExtractor;
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
class MessagesController {

	private final ProxyFacade proxyFacade;
	private final ProviderDispatcher providerDispatcher;
	private final RateLimiter rateLimiter;
	private final TokenEstimator tokenEstimator;

	@SuppressWarnings("unchecked")
	@PostMapping("/v1/messages")
	void messages(
			@RequestBody Map<String, Object> request,
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

		String model = (String) request.getOrDefault(
				"model",
				"claude-sonnet-4-5"
		);
		boolean stream = Boolean.TRUE.equals(request.get("stream"));

		List<Map<String, Object>> openAiMessages = convertAnthropicToOpenAi(
				request);
		List<Map<String, Object>> tools = (List<Map<String, Object>>) request.get(
				"tools");
		int inputTokens = tokenEstimator.estimateRequest(openAiMessages, tools);

		HashMap<String, Object> openAiReq = new HashMap<>();
		openAiReq.put("messages", openAiMessages);
		openAiReq.put("stream", stream);
		if (tools != null) openAiReq.put("tools", tools);

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
						ProxyFacade.Format.ANTHROPIC,
						stream,
						actualModel,
						inputTokens,
						keyId,
						keyId,
						httpReq
				),
				httpRes
		);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> convertAnthropicToOpenAi(Map<String, Object> request) {
		List<Map<String, Object>> result = new ArrayList<>();

		if (request.containsKey("system")) {
			Object sys = request.get("system");
			String systemText;
			if (sys instanceof String s) {
				systemText = s;
			} else if (sys instanceof List<?> list) {
				StringBuilder sb = new StringBuilder();
				for (Object item : list) {
					if (item instanceof Map<?, ?> m && m.containsKey("text"))
						sb.append(m.get("text"));
				}
				systemText = sb.toString();
			} else {
				systemText = sys.toString();
			}
			result.add(Map.of("role", "system", "content", systemText));
		}

		List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get(
				"messages");
		if (messages != null) {
			for (Map<String, Object> msg : messages) {
				String role = (String) msg.get("role");
				Object content = msg.get("content");
				if (content instanceof List<?> list && ContentExtractor.hasImageBlock(list)) {
					result.add(Map.of("role", role, "content", list));
				} else {
					String textContent;
					if (content instanceof String s) {
						textContent = s;
					} else if (content instanceof List<?> list2) {
						StringBuilder sb = new StringBuilder();
						for (Object item : list2) {
							if (item instanceof Map<?, ?> m) {
								if ("text".equals(m.get("type")))
									sb.append(m.get("text"));
							}
						}
						textContent = sb.toString();
					} else {
						textContent = content != null ? content.toString() : "";
					}
					result.add(Map.of("role", role, "content", textContent));
				}
			}
		}

		return result;
	}
}
