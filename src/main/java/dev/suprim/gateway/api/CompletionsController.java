package dev.suprim.gateway.api;

import dev.suprim.gateway.antigravity.AntigravityFacade;
import dev.suprim.gateway.auth.Provider;
import dev.suprim.gateway.model.ModelRouter;
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

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
class CompletionsController {

	private final ProxyFacade proxyFacade;
	private final AntigravityFacade antigravityFacade;
	private final RateLimiter rateLimiter;
	private final TokenEstimator tokenEstimator;

	@SuppressWarnings("unchecked")
	@PostMapping("/v1/chat/completions")
	void completions(
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
		boolean stream = Boolean.TRUE.equals(request.get("stream"));
		List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get(
				"messages");
		List<Map<String, Object>> tools = (List<Map<String, Object>>) request.get(
				"tools");
		int inputTokens = tokenEstimator.estimateRequest(messages, tools);

		Provider provider = ModelRouter.resolveProvider(model);
		if (provider == Provider.ANTIGRAVITY) {
			antigravityFacade.handle(
					request, model, stream, inputTokens, keyId,
					RequestContext.clientIp(httpReq), httpRes
			);
			return;
		}

		proxyFacade.handle(
				ProxyFacade.buildRequest(
						request,
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
