package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.MessagesRequest;
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
import tools.jackson.databind.JsonNode;

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

		String model = request.model();
		boolean stream = Boolean.TRUE.equals(request.stream());

		List<Map<String, Object>> openAiMessages = convertAnthropicToOpenAi(
				request);
		List<Map<String, Object>> tools = RequestMapper.toolsToList(
				request.tools()
		);
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

	private List<Map<String, Object>> convertAnthropicToOpenAi(MessagesRequest request) {
		List<Map<String, Object>> result = new ArrayList<>();

		JsonNode sys = request.system();
		if (sys != null) {
			String systemText;
			if (sys.isString()) {
				systemText = sys.stringValue();
			} else if (sys.isArray()) {
				StringBuilder sb = new StringBuilder();
				for (JsonNode item : sys) {
					if (item.has("text")) sb.append(item.get("text")
					                                    .stringValue());
				}
				systemText = sb.toString();
			} else {
				systemText = sys.toString();
			}
			result.add(Map.of("role", "system", "content", systemText));
		}

		if (request.messages() != null) {
			for (MessagesRequest.Message msg : request.messages()) {
				String role = msg.role();
				JsonNode content = msg.content();
				if (content == null) {
					result.add(Map.of("role", role, "content", ""));
				} else if (content.isString()) {
					result.add(Map.of(
							"role",
							role,
							"content",
							content.stringValue()
					));
				} else if (content.isArray() && hasImageBlock(content)) {
					List<Object> parts = RequestMapper.toList(content);
					result.add(Map.of("role", role, "content", parts));
				} else if (content.isArray()) {
					StringBuilder sb = new StringBuilder();
					for (JsonNode item : content) {
						if (item.has("type") && "text".equals(item.get("type")
						                                          .stringValue()))
							sb.append(item.get("text").stringValue());
					}
					result.add(Map.of("role", role, "content", sb.toString()));
				} else {
					result.add(Map.of(
							"role",
							role,
							"content",
							content.toString()
					));
				}
			}
		}

		return result;
	}

	private boolean hasImageBlock(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "image".equals(item.get("type")
			                                           .stringValue()))
				return true;
		}
		return false;
	}
}
