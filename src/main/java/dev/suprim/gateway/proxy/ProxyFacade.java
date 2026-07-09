package dev.suprim.gateway.proxy;

import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.proxy.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.utils.ErrorResponse;
import dev.suprim.gateway.utils.RequestContext;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class ProxyFacade {

	private static final Logger log = LoggerFactory.getLogger(ProxyFacade.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final UpstreamCaller upstreamCaller;
	private final StreamHandler streamHandler;
	private final StreamConverter streamConverter;
	private final RequestLogPublisher logPublisher;
	private final VirtualKeyService keyService;

	public enum Format {OPENAI, ANTHROPIC, RESPONSES}

	public record ProxyRequest(
			Map<String, Object> openAiRequest, Format format, boolean stream,
			String model, int inputTokens, String keyId, String virtualKeyId,
			String clientIp
	) {}

	public void handle(
			ProxyRequest req,
			HttpServletResponse httpRes
	) throws Exception {
		long startTime = System.currentTimeMillis();

		KiroResponse response = upstreamCaller.call(
				req.openAiRequest(),
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
			Map<String, Object> openAiRequest, Format format, boolean stream,
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

		writer.write(preamble(req.format(), id, req.model()));
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
				result.firstTokenMs()
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
		String content = streamHandler.collectContent(response);
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

		publishLog(req, outputTokens, false, startTime, null);
		if (req.virtualKeyId() != null && outputTokens > 0)
			keyService.incrementUsage(req.virtualKeyId(), outputTokens);
	}

	private String preamble(
			Format format,
			String id,
			String model
	) throws Exception {
		return switch (format) {
			case OPENAI -> "";
			case ANTHROPIC -> streamConverter.toAnthropicPreamble(id, model);
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
		return switch (format) {
			case OPENAI -> streamConverter.toOpenAiChunk(event, model, id);
			case ANTHROPIC ->
					("content".equals(event.type()) && event.content() != null)
							? streamConverter.toAnthropicDelta(event.content()) : null;
			case RESPONSES -> {
				if ("content".equals(event.type()) && event.content() != null)
					yield streamConverter.toResponsesTextDelta(event.content());
				if ("tool_use".equals(event.type()) && event.toolStop())
					yield streamConverter.toResponsesToolCall(event, 1);
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
			case OPENAI -> streamConverter.toOpenAiStopChunk(model, id) +
			               streamConverter.toOpenAiDone();
			case ANTHROPIC ->
					streamConverter.toAnthropicFinale(result.outputTokens());
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
			case OPENAI -> streamConverter.toOpenAiNonStreaming(
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
			case OPENAI -> "chatcmpl-" + UUID.randomUUID();
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

	private void publishLog(
			ProxyRequest req,
			int outputTokens,
			boolean streaming,
			long startTime,
			Long firstTokenMs
	) {
		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(req.keyId())
				               .model(req.model())
				               .requestedModel(req.model())
				               .status(200)
				               .promptTokens(req.inputTokens())
				               .completionTokens(outputTokens >
				                                 0 ? outputTokens : null)
				               .latencyMs(latency)
				               .firstTokenMs(firstTokenMs !=
				                             null ? firstTokenMs.intValue() : null)
				               .streaming(streaming)
				               .clientIp(req.clientIp())
				               .build()
		);
	}
}
