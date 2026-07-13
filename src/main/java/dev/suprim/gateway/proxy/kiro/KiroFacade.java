package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.kiro.payload.PayloadBuilder;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.StreamHandler;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.utils.ErrorResponse;
import dev.suprim.gateway.utils.RequestContext;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.nonNull;

@RequiredArgsConstructor
@Component
@Slf4j
public class KiroFacade {

	private final JsonMapper mapper = new JsonMapper();
	private final KiroHttpClient kiroClient;
	private final PayloadBuilder payloadBuilder;
	private final KiroAuthManager auth;
	private final StreamHandler streamHandler;
	private final StreamConverter streamConverter;
	private final RequestLogPublisher logPublisher;
	private final VirtualKeyService keyService;

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
		ProxyRequest req = new ProxyRequest(
				request,
				format,
				stream,
				model,
				inputTokens,
				keyId,
				keyId,
				clientIp
		);
		handle(req, httpRes);
	}

	public void handle(
			ProxyRequest req,
			HttpServletResponse httpRes
	) throws Exception {
		long startTime = System.currentTimeMillis();

		KiroResponse response = callUpstream(
				req.request(),
				req.stream() || req.format() == Format.RESPONSES
		);

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

	public static ProxyRequest buildRequest(
			InternalRequest openAiRequest, Format format, boolean stream,
			String model, int inputTokens, String keyId, String virtualKeyId,
			HttpServletRequest httpReq
	) {
		return new ProxyRequest(
				openAiRequest,
				format,
				stream,
				model,
				inputTokens,
				keyId,
				virtualKeyId,
				RequestContext.clientIp(httpReq)
		);
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
		String id = generateId(req.format());

		writer.write(
				preamble(
						req.format(),
						id,
						req.model(),
						req.inputTokens()
				)
		);
		writer.flush();

		StreamHandler.StreamResult result = streamHandler.streamToWriter(
				response,
				writer,
				event -> convertEvent(event, req.format(), req.model(), id),
				startTime
		);

		writer.write(
				finale(
						req.format(),
						id,
						req.model(),
						result,
						req.inputTokens()
				)
		);
		writer.flush();

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
		double credits = collected.credits();
		int outputTokens = streamHandler.countTokens(content);
		String id = generateId(req.format());

		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");
		mapper.writeValue(
				httpRes.getWriter(),
				nonStreamBody(
						req.format(),
						id,
						req.model(),
						content,
						req.inputTokens(),
						outputTokens
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

	private String preamble(
			Format format,
			String id,
			String model,
			int inputTokens
	) throws Exception {
		return switch (format) {
			case COMPLETION -> "";
			case ANTHROPIC -> streamConverter.toAnthropicPreamble(
					id,
					model,
					inputTokens
			);
			case RESPONSES -> streamConverter.toResponsesCreated(id, model)
			                  + streamConverter.toResponsesOutputItemAdded(id)
			                  + streamConverter.toResponsesContentPartAdded();
		};
	}

	private String convertEvent(
			KiroEvent event,
			Format format,
			String model,
			String id
	) throws Exception {
		final boolean hasContent =
				"content".equals(event.type()) && nonNull(event.content());
		final boolean stopSign =
				"tool_use".equals(event.type()) && event.toolStop();

		return switch (format) {
			case COMPLETION -> streamConverter.toOpenAiChunk(event, model, id);
			case ANTHROPIC -> {
				if (hasContent) {
					yield streamConverter.toAnthropicDelta(event.content());
				}

				if (stopSign) {
					yield streamConverter.toAnthropicToolUse(event, 1);
				}

				yield null;
			}
			case RESPONSES -> {
				if (hasContent) {
					yield streamConverter.toResponsesTextDelta(event.content());
				}

				if (stopSign) {
					yield streamConverter.toResponsesToolCall(event, 1);
				}

				yield null;
			}
		};
	}

	private String finale(
			Format format,
			String id,
			String model,
			StreamHandler.StreamResult result,
			int inputTokens
	) throws Exception {
		return switch (format) {
			case COMPLETION -> streamConverter.toOpenAiStopChunk(model, id) +
			                   streamConverter.toOpenAiDone();
			case ANTHROPIC -> streamConverter.toAnthropicFinale(
					result.outputTokens(),
					result.hasToolUse()
			);
			case RESPONSES ->
					streamConverter.toResponsesTextDone(result.content(), id)
					+ streamConverter.toResponsesCompleted(
							id,
							model,
							result.content(),
							List.of(),
							inputTokens,
							result.outputTokens()
					);
		};
	}

	private Object nonStreamBody(
			Format format,
			String id,
			String model,
			String content,
			int inputTokens,
			int outputTokens
	) {
		return switch (format) {
			case COMPLETION -> streamConverter.toOpenAiNonStreaming(
					List.of(KiroEvent.content(content)), model
			);
			case ANTHROPIC -> streamConverter.toAnthropicNonStreaming(
					id,
					model,
					content,
					inputTokens,
					outputTokens
			);
			case RESPONSES -> streamConverter.toResponsesNonStreaming(
					id,
					model,
					content,
					inputTokens,
					outputTokens
			);
		};
	}

	private String generateId(Format format) {
		return switch (format) {
			case COMPLETION -> "chatcmpl-" + UUID.randomUUID();
			case ANTHROPIC -> "msg_" + UUID.randomUUID().toString().replace(
					"-",
					""
			).substring(0, 20);
			case RESPONSES -> "resp_" + UUID.randomUUID()
			                                .toString()
			                                .replace("-", "")
			                                .substring(0, 24);
		};
	}

	private KiroResponse callUpstream(
			InternalRequest request,
			boolean stream
	) throws Exception {
		String payload = payloadBuilder.buildOpenAiPayload(request, auth);
		String url = auth.getApiHost() + "/generateAssistantResponse";

		KiroResponse response = kiroClient.request(
				"POST",
				url,
				payload,
				stream,
				auth.getAccessToken()
		);
		if (response.status() == 403) {
			log.warn("[Kiro] 403, refreshing token and retrying");
			auth.forceRefresh();
			response = kiroClient.request("POST", url, payload, stream, auth.getAccessToken());
		}
		return response;
	}

	private void publishLog(
			ProxyRequest req,
			int outputTokens,
			boolean streaming,
			long startTime,
			Long firstTokenMs,
			Double credits
	) {
		int latency = (int) (System.currentTimeMillis() - startTime);
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
				               .latencyMs(latency)
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
