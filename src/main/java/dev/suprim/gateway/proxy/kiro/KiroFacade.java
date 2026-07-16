package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.StreamConverter;
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

	public void handle(
			InternalRequest request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			Format format,
			HttpServletResponse httpRes
	) throws Exception {
		handle(
				ProxyRequest.builder()
				            .request(request)
				            .format(format)
				            .stream(stream)
				            .model(model)
				            .inputTokens(inputTokens)
				            .keyId(keyId)
				            .virtualKeyId(keyId)
				            .clientIp(clientIp)
				            .build(),
				httpRes
		);
	}

	public void handle(
			ProxyRequest req,
			HttpServletResponse httpRes
	) throws Exception {
		long startTime = System.currentTimeMillis();

		KiroResponse response;
		try {
			response = upstreamDispatcher.dispatch(
					req.request(),
					req.stream() || req.format() == Format.RESPONSES
			);
		} catch (RuntimeException e) {
			httpRes.setStatus(503);
			httpRes.setContentType("application/json");
			httpRes.getWriter().write(
					"{\"error\":{\"message\":\"" + e.getMessage() + "\",\"type\":\"service_unavailable\"}}"
			);
			return;
		}

		if (response.status() != 200) {
			handleError(response, req, startTime, httpRes);
			return;
		}

		if (req.stream()) {
			handleStream(httpRes, response, req, startTime);
		} else {
			handleNonStream(httpRes, response, req, startTime);
		}
	}

	private void handleError(
			KiroResponse response, ProxyRequest req, long startTime,
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
		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(req.keyId())
				               .accountId(auth.getDisplayName())
				               .model(req.model())
				               .requestedModel(req.model())
				               .status(response.status())
				               .promptTokens(req.inputTokens())
				               .latencyMs(latency)
				               .streaming(req.stream())
				               .clientIp(req.clientIp())
				               .errorMessage(body.length() >
				                             200 ? body.substring(
						               0,
						               200
				               ) : body)
				               .build()
		);
		if (req.format() == Format.ANTHROPIC) {
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
	}

	private void handleStream(
			HttpServletResponse httpRes,
			KiroResponse response,
			ProxyRequest req,
			long startTime
	) throws Exception {
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("text/event-stream; charset=utf-8");
		httpRes.setHeader("Cache-Control", "no-cache");
		PrintWriter writer = httpRes.getWriter();

		boolean thinkingEnabled = req.format() != Format.ANTHROPIC
		                          || req.request().thinkingEnabled();

		StreamingEventWriter eventWriter = new StreamingEventWriter(
				writer, streamConverter, req.format(), req.model(),
				thinkingEnabled
		);

		StreamHandler.StreamResult result = streamHandler.streamToWriter(
				response,
				writer,
				eventWriter,
				startTime
		);

		eventWriter.finish();

		publishLog(
				req,
				result.outputTokens(),
				true,
				startTime,
				result.firstTokenMs(),
				result.credits() > 0 ? result.credits() : null
		);
		if (req.virtualKeyId() != null && result.outputTokens() > 0)
			keyService.incrementUsage(
					req.virtualKeyId(),
					result.outputTokens()
			);
	}

	private void handleNonStream(
			HttpServletResponse httpRes,
			KiroResponse response,
			ProxyRequest req,
			long startTime
	) throws Exception {
		StreamHandler.CollectResult collected = streamHandler.collectContent(
				response);
		String content = collected.content();
		String reasoning = collected.reasoning();
		double credits = collected.credits();
		int outputTokens = streamHandler.countTokens(content);
		String id = formatConverter.generateId(req.format());

		boolean emitReasoning = reasoning != null &&
				(req.format() != Format.ANTHROPIC || req.request().thinkingEnabled());

		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");
		mapper.writeValue(
				httpRes.getWriter(),
				formatConverter.nonStreamBody(
						req.format(), id, req.model(), content,
						emitReasoning ? reasoning : null,
						req.inputTokens(), outputTokens
				)
		);

		publishLog(
				req,
				outputTokens,
				false,
				startTime,
				null,
				credits > 0 ? credits : null
		);
		if (req.virtualKeyId() != null && outputTokens > 0)
			keyService.incrementUsage(req.virtualKeyId(), outputTokens);
	}

	private void publishLog(
			ProxyRequest req,
			int outputTokens,
			boolean streaming,
			long startTime,
			Long firstTokenMs,
			Double credits
	) {
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(req.keyId())
				               .accountId(auth.getDisplayName())
				               .model(req.model())
				               .requestedModel(req.model())
				               .status(200)
				               .promptTokens(req.inputTokens())
				               .completionTokens(
						               outputTokens >
						               0 ? outputTokens : null
				               )
				               .latencyMs(
						               (int) (
								               System.currentTimeMillis() -
								               startTime
						               )
				               )
				               .firstTokenMs(
						               firstTokenMs !=
						               null ? firstTokenMs.intValue() : null
				               )
				               .streaming(streaming)
				               .clientIp(req.clientIp())
				               .credits(credits != null &&
				                        credits > 0 ? credits : null
				               )
				               .build()
		);
	}
}
