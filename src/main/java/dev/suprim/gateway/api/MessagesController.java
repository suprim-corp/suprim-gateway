package dev.suprim.gateway.api;

import dev.suprim.gateway.auth.KiroAuthManager;
import dev.suprim.gateway.logging.RequestLogService;
import dev.suprim.gateway.proxy.KiroEventParser;
import dev.suprim.gateway.proxy.KiroEventParser.KiroEvent;
import dev.suprim.gateway.proxy.KiroHttpClient;
import dev.suprim.gateway.proxy.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.proxy.PayloadBuilder;
import dev.suprim.gateway.utils.TokenEstimator;
import dev.suprim.gateway.virtualkey.RateLimiter;
import dev.suprim.gateway.virtualkey.VirtualKey;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class MessagesController {

	private static final Logger log = LoggerFactory.getLogger(MessagesController.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final KiroHttpClient kiroClient;
	private final PayloadBuilder payloadBuilder;
	private final KiroAuthManager auth;
	private final RequestLogService logService;
	private final VirtualKeyService keyService;
	private final RateLimiter rateLimiter;
	private final TokenEstimator tokenEstimator;

	MessagesController(
			KiroHttpClient kiroClient, PayloadBuilder payloadBuilder,
			KiroAuthManager auth, RequestLogService logService,
			VirtualKeyService keyService, RateLimiter rateLimiter,
			TokenEstimator tokenEstimator
	) {
		this.kiroClient = kiroClient;
		this.payloadBuilder = payloadBuilder;
		this.auth = auth;
		this.logService = logService;
		this.keyService = keyService;
		this.rateLimiter = rateLimiter;
		this.tokenEstimator = tokenEstimator;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/v1/messages")
	void messages(
			@RequestBody Map<String, Object> request,
			HttpServletRequest httpReq, HttpServletResponse httpRes
	) throws Exception {
		Authentication authentication = SecurityContextHolder.getContext()
		                                                     .getAuthentication();
		VirtualKey key = resolveKey(authentication);
		String keyId = key != null ? key.id() : null;

		if (key != null && !rateLimiter.isAllowed(
				key.id(),
				key.rateLimitPerMin()
		)) {
			httpRes.setStatus(429);
			httpRes.setContentType("application/json");
			httpRes.getWriter().write(
					"{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Rate limit exceeded\"}}");
			return;
		}

		String model = (String) request.getOrDefault(
				"model",
				"claude-sonnet-4-5"
		);
		boolean stream = Boolean.TRUE.equals(request.get("stream"));
		long startTime = System.currentTimeMillis();
		int inputTokens = 0;

		try {
			List<Map<String, Object>> openAiMessages = convertAnthropicToOpenAi(
					request);
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> tools = (List<Map<String, Object>>) request.get("tools");
			inputTokens = tokenEstimator.estimateRequest(openAiMessages, tools);

			HashMap<String, Object> openAiReq = new HashMap<>();
			openAiReq.put("messages", openAiMessages);
			openAiReq.put("model", model);
			if (tools != null) openAiReq.put("tools", tools);

			String payload = payloadBuilder.buildOpenAiPayload(openAiReq, auth);
			String url = kiroClient.getGenerateUrl();
			KiroResponse response = kiroClient.request(
					"POST",
					url,
					payload,
					stream
			);

			if (response.status() != 200) {
				String body = new String(response.body().readAllBytes());
				log.error(
						"[Messages] Upstream {} body: {}",
						response.status(),
						body.length() > 500 ? body.substring(0, 500) : body
				);
				int latency = (int) (System.currentTimeMillis() - startTime);
				logService.log(
						keyId,
						null,
						model,
						model,
						response.status(),
						inputTokens,
						null,
						latency,
						null,
						stream,
						clientIp(httpReq),
						body.length() > 200 ? body.substring(0, 200) : body
				);
				httpRes.setStatus(response.status());
				httpRes.setContentType("application/json");
				httpRes.getWriter().write(
						"{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Upstream error\"}}");
				return;
			}

			if (stream) {
				httpRes.setContentType("text/event-stream");
				httpRes.setHeader("Cache-Control", "no-cache");
				PrintWriter writer = httpRes.getWriter();
				KiroEventParser parser = new KiroEventParser();
				String msgId = "msg_" + UUID.randomUUID()
				                            .toString()
				                            .replace("-", "")
				                            .substring(0, 20);
				int totalTokens = 0;
				int blockIdx = 0;

				writer.write("event: message_start\ndata: " +
				             mapper.writeValueAsString(Map.of(
						             "type",
						             "message_start",
						             "message",
						             Map.of(
								             "id",
								             msgId,
								             "type",
								             "message",
								             "role",
								             "assistant",
								             "content",
								             List.of(),
								             "model",
								             model
						             )
				             )) + "\n\n");

				writer.write("event: content_block_start\ndata: " +
				             mapper.writeValueAsString(Map.of(
						             "type",
						             "content_block_start",
						             "index",
						             blockIdx,
						             "content_block",
						             Map.of("type", "text", "text", "")
				             )) + "\n\n");
				writer.flush();

				byte[] buf = new byte[8192];
				int read;
				while ((read = response.body().read(buf)) != -1) {
					byte[] chunk = new byte[read];
					System.arraycopy(buf, 0, chunk, 0, read);
					List<KiroEvent> events = parser.feed(chunk);
					for (KiroEvent event : events) {
						if ("content".equals(event.type()) &&
						    event.content() != null) {
							writer.write("event: content_block_delta\ndata: " +
							             mapper.writeValueAsString(Map.of(
									             "type",
									             "content_block_delta",
									             "index",
									             blockIdx,
									             "delta",
									             Map.of(
											             "type",
											             "text_delta",
											             "text",
											             event.content()
									             )
							             )) + "\n\n");
							writer.flush();
							totalTokens += tokenEstimator.countTokens(event.content());
						}
					}
				}

				writer.write("event: content_block_stop\ndata: " +
				             mapper.writeValueAsString(Map.of(
						             "type",
						             "content_block_stop",
						             "index",
						             blockIdx
				             )) + "\n\n");

				writer.write("event: message_delta\ndata: " +
				             mapper.writeValueAsString(Map.of(
						             "type",
						             "message_delta",
						             "delta",
						             Map.of("stop_reason", "end_turn"),
						             "usage",
						             Map.of("output_tokens", totalTokens)
				             )) + "\n\n");
				writer.write(
						"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
				writer.flush();

				int latency = (int) (System.currentTimeMillis() - startTime);
				logService.log(
						keyId,
						null,
						model,
						model,
						200,
						inputTokens,
						totalTokens,
						latency,
						null,
						true,
						clientIp(httpReq),
						null
				);
				if (key != null && totalTokens > 0) keyService.incrementUsage(
						key.id(),
						totalTokens
				);
			} else {
				List<KiroEvent> events = KiroEventParser.parseStream(response.body());
				StringBuilder content = new StringBuilder();
				for (KiroEvent event : events) {
					if ("content".equals(event.type()) &&
					    event.content() != null)
						content.append(event.content());
				}

				String msgId = "msg_" + UUID.randomUUID()
				                            .toString()
				                            .replace("-", "")
				                            .substring(0, 20);
				int outputTokens = tokenEstimator.countTokens(content.toString());
				Map<String, Object> result = Map.of(
						"id",
						msgId,
						"type",
						"message",
						"role",
						"assistant",
						"content",
						List.of(Map.of(
								"type",
								"text",
								"text",
								content.toString()
						)),
						"model",
						model,
						"stop_reason",
						"end_turn",
						"usage",
						Map.of("input_tokens", 0, "output_tokens", outputTokens)
				);
				httpRes.setContentType("application/json");
				mapper.writeValue(httpRes.getWriter(), result);

				int latency = (int) (System.currentTimeMillis() - startTime);
				logService.log(
						keyId,
						null,
						model,
						model,
						200,
						inputTokens,
						outputTokens,
						latency,
						null,
						false,
						clientIp(httpReq),
						null
				);
			}
		} catch (Exception e) {
			log.error("[Messages] Error: {}", e.getMessage(), e);
			int latency = (int) (System.currentTimeMillis() - startTime);
			logService.log(
					keyId,
					null,
					model,
					model,
					500,
					inputTokens,
					null,
					latency,
					null,
					stream,
					clientIp(httpReq),
					e.getMessage()
			);
			if (!httpRes.isCommitted()) {
				httpRes.setStatus(500);
				httpRes.setContentType("application/json");
				httpRes.getWriter().write(
						"{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal server error\"}}");
			}
		}
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
				String textContent;
				if (content instanceof String s) {
					textContent = s;
				} else if (content instanceof List<?> list) {
					StringBuilder sb = new StringBuilder();
					for (Object item : list) {
						if (item instanceof Map<?, ?> m) {
							if ("text".equals(m.get("type"))) sb.append(m.get(
									"text"));
						}
					}
					textContent = sb.toString();
				} else {
					textContent = content != null ? content.toString() : "";
				}
				result.add(Map.of("role", role, "content", textContent));
			}
		}

		return result;
	}

	private VirtualKey resolveKey(Authentication auth) {
		if (auth != null && auth.getDetails() instanceof VirtualKey k) return k;
		return null;
	}

	private String clientIp(HttpServletRequest req) {
		String xff = req.getHeader("X-Forwarded-For");
		return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
	}
}
