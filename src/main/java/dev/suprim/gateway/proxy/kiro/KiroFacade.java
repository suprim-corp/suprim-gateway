package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.logging.ProviderOutcome;
import dev.suprim.gateway.logging.RequestLogCall;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.SseHeartbeat;
import dev.suprim.gateway.proxy.StreamHandler;
import dev.suprim.gateway.proxy.StreamingEventWriter;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.utils.ErrorResponse;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.io.PrintWriter;

@RequiredArgsConstructor
@Component
@Slf4j
public class KiroFacade {

	private final JsonMapper mapper = new JsonMapper();
	private final KiroAuthManager auth;
	private final StreamHandler streamHandler;
	private final StreamConverter streamConverter;
	private final SseHeartbeat sseHeartbeat;
	private final RequestLogPublisher logPublisher;
	private final VirtualKeyService keyService;
	private final KiroUpstreamDispatcher upstreamDispatcher;
	private final KiroFormatConverter formatConverter;

	@Builder
	public record ProxyRequest(
			InternalRequest request, Format format, boolean stream,
			String model, int inputTokens, String keyId, String virtualKeyId,
			String clientIp
	) {}

	ProviderOutcome handle(
			InternalRequest request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			Format format,
			HttpServletResponse httpRes
	) throws Exception {
		ProviderOutcome outcome = handle(
				request,
				RequestLogCall.start(
						model,
						stream,
						inputTokens,
						keyId,
						clientIp,
						format
				),
				httpRes
		);
		if (outcome.event() != null) {
			logPublisher.publish(outcome.event());
		}
		return outcome;
	}

	public ProviderOutcome handle(
			InternalRequest request,
			RequestLogCall call,
			HttpServletResponse httpRes
	) throws Exception {
		ProxyRequest proxyRequest = ProxyRequest.builder()
		                                        .request(request)
		                                        .format(call.format())
		                                        .stream(call.streaming())
		                                        .model(call.model())
		                                        .inputTokens(call.estimatedInputTokens())
		                                        .keyId(call.virtualKeyId())
		                                        .virtualKeyId(call.virtualKeyId())
		                                        .clientIp(call.clientIp())
		                                        .build();
		return handle(proxyRequest, call, httpRes);
	}

	private ProviderOutcome handle(
			ProxyRequest req,
			RequestLogCall call,
			HttpServletResponse httpRes
	) throws Exception {
		KiroUpstreamDispatcher.DispatchResult dispatchResult;
		try {
			dispatchResult = upstreamDispatcher.dispatch(
					req.request(),
					req.stream() || req.format() == Format.RESPONSES
			);
		} catch (RuntimeException exception) {
			httpRes.setStatus(503);
			httpRes.setContentType("application/json");
			httpRes.getWriter().write(
					"{\"error\":{\"message\":\"" + exception.getMessage() +
					"\",\"type\":\"service_unavailable\"}}"
			);
			return ProviderOutcome.none();
		}

		KiroResponse response = dispatchResult.response();
		String accountId = dispatchResult.accountId();
		if (response.status() != 200) {
			return handleError(response, call, accountId, httpRes);
		}

		return req.stream()
				? handleStream(httpRes, response, req, call, accountId)
				: handleNonStream(httpRes, response, req, call, accountId);
	}

	private ProviderOutcome handleError(
			KiroResponse response,
			RequestLogCall call,
			String accountId,
			HttpServletResponse httpRes
	) throws Exception {
		String body;
		try (InputStream is = response.body()) {
			body = new String(is.readAllBytes());
		}
		log.error(
				"[Proxy] Upstream {} body: {}",
				response.status(),
				body.length() > 500 ? body.substring(0, 500) : body
		);
		if (call.format() == Format.ANTHROPIC) {
			ErrorResponse.anthropic(
					httpRes,
					response.status(),
					"Upstream error",
					"api_error"
			);
		} else {
			ErrorResponse.openAi(
					httpRes,
					response.status(),
					"Upstream error",
					"upstream_error"
			);
		}
		return call.upstreamError(accountId, response.status(), body);
	}

	private ProviderOutcome handleStream(
			HttpServletResponse httpRes,
			KiroResponse response,
			ProxyRequest req,
			RequestLogCall call,
			String accountId
	) throws Exception {
		try (SseHeartbeat.Session session = sseHeartbeat.open(httpRes)) {
			PrintWriter writer = session.writer();

			boolean thinkingEnabled = req.format() != Format.ANTHROPIC
			                          || req.request().thinkingEnabled();

			StreamingEventWriter eventWriter = new StreamingEventWriter(
					writer, streamConverter, req.format(), req.model(),
					thinkingEnabled, req.inputTokens()
			);

			StreamHandler.StreamResult result = streamHandler.streamToWriter(
					response,
					writer,
					eventWriter,
					call.startedAt()
			);

			eventWriter.finish(result.outputTokens());

			if (req.virtualKeyId() != null && result.outputTokens() > 0) {
				keyService.incrementUsage(
						req.virtualKeyId(),
						result.outputTokens()
				);
			}
			return call.success(
					accountId,
					null,
					result.outputTokens(),
					result.firstTokenMs(),
					result.credits()
			);
		}
	}

	private ProviderOutcome handleNonStream(
			HttpServletResponse httpRes,
			KiroResponse response,
			ProxyRequest req,
			RequestLogCall call,
			String accountId
	) throws Exception {
		StreamHandler.CollectResult collected = streamHandler.collectContent(
				response);
		String content = collected.content();
		String reasoning = collected.reasoning();
		double credits = collected.credits();
		StreamHandler.Usage usage = collected.usage();
		int estimatedOutputTokens = streamHandler.countTokens(content);
		int outputTokens = usage != null && usage.outputTokens() != null
				? usage.outputTokens()
				: estimatedOutputTokens;
		int inputTokens = usage != null && usage.promptTokens() != null
				? usage.promptTokens()
				: req.inputTokens();
		String id = formatConverter.generateId(req.format());

		boolean emitReasoning = reasoning != null &&
		                        (req.format() != Format.ANTHROPIC ||
		                         req.request().thinkingEnabled());

		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");
		mapper.writeValue(
				httpRes.getWriter(),
				formatConverter.nonStreamBody(
						req.format(), id, req.model(), content,
						emitReasoning ? reasoning : null,
						inputTokens,
						outputTokens,
						usage
				)
		);

		if (req.virtualKeyId() != null && outputTokens > 0) {
			keyService.incrementUsage(req.virtualKeyId(), outputTokens);
		}
		return call.success(accountId, null, outputTokens, null, credits);
	}
}
