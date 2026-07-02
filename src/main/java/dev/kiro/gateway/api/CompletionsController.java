package dev.kiro.gateway.api;

import dev.kiro.gateway.auth.KiroAuthManager;
import dev.kiro.gateway.logging.RequestLogService;
import dev.kiro.gateway.proxy.KiroEventParser;
import dev.kiro.gateway.proxy.KiroEventParser.KiroEvent;
import dev.kiro.gateway.proxy.KiroHttpClient;
import dev.kiro.gateway.proxy.KiroHttpClient.KiroResponse;
import dev.kiro.gateway.proxy.PayloadBuilder;
import dev.kiro.gateway.proxy.StreamConverter;
import dev.kiro.gateway.virtualkey.RateLimiter;
import dev.kiro.gateway.virtualkey.VirtualKey;
import dev.kiro.gateway.virtualkey.VirtualKeyService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class CompletionsController {

    private static final Logger log = LoggerFactory.getLogger(CompletionsController.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final KiroHttpClient kiroClient;
    private final PayloadBuilder payloadBuilder;
    private final StreamConverter streamConverter;
    private final KiroAuthManager auth;
    private final RequestLogService logService;
    private final VirtualKeyService keyService;
    private final RateLimiter rateLimiter;

    CompletionsController(KiroHttpClient kiroClient, PayloadBuilder payloadBuilder,
                          StreamConverter streamConverter, KiroAuthManager auth,
                          RequestLogService logService, VirtualKeyService keyService,
                          RateLimiter rateLimiter) {
        this.kiroClient = kiroClient;
        this.payloadBuilder = payloadBuilder;
        this.streamConverter = streamConverter;
        this.auth = auth;
        this.logService = logService;
        this.keyService = keyService;
        this.rateLimiter = rateLimiter;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/v1/chat/completions")
    void completions(@RequestBody Map<String, Object> request,
                     HttpServletRequest httpReq, HttpServletResponse httpRes) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String keyId = resolveKeyId(authentication);
        VirtualKey key = resolveKey(authentication);

        if (key != null && !rateLimiter.isAllowed(key.id(), key.rateLimitPerMin())) {
            httpRes.setStatus(429);
            httpRes.setContentType("application/json");
            httpRes.getWriter().write("{\"error\":{\"message\":\"Rate limit exceeded\",\"type\":\"rate_limit_error\"}}");
            return;
        }

        String model = (String) request.getOrDefault("model", "claude-sonnet-4-5");
        boolean stream = Boolean.TRUE.equals(request.get("stream"));
        long startTime = System.currentTimeMillis();

        try {
            String payload = payloadBuilder.buildOpenAiPayload(request, auth);
            String url = kiroClient.getGenerateUrl();
            KiroResponse response = kiroClient.request("POST", url, payload, stream);

            if (response.status() != 200) {
                String body = new String(response.body().readAllBytes());
                int latency = (int) (System.currentTimeMillis() - startTime);
                logService.log(keyId, null, model, model, response.status(), null, null, latency, null, stream, clientIp(httpReq), body.length() > 200 ? body.substring(0, 200) : body);
                httpRes.setStatus(response.status());
                httpRes.setContentType("application/json");
                httpRes.getWriter().write("{\"error\":{\"message\":\"Upstream error\",\"type\":\"upstream_error\",\"code\":" + response.status() + "}}");
                return;
            }

            if (stream) {
                httpRes.setContentType("text/event-stream");
                httpRes.setHeader("Cache-Control", "no-cache");
                httpRes.setHeader("Connection", "keep-alive");
                PrintWriter writer = httpRes.getWriter();
                KiroEventParser parser = new KiroEventParser();
                byte[] buf = new byte[8192];
                String chatId = "chatcmpl-" + UUID.randomUUID();
                int totalTokens = 0;
                int read;

                while ((read = response.body().read(buf)) != -1) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buf, 0, chunk, 0, read);
                    List<KiroEvent> events = parser.feed(chunk);
                    for (KiroEvent event : events) {
                        String sse = streamConverter.toOpenAiChunk(event, model, chatId);
                        if (sse != null) {
                            writer.write(sse);
                            writer.flush();
                            if ("content".equals(event.type())) totalTokens += estimateTokens(event.content());
                        }
                    }
                }

                writer.write(streamConverter.toOpenAiStopChunk(model, chatId));
                writer.write(streamConverter.toOpenAiDone());
                writer.flush();

                int latency = (int) (System.currentTimeMillis() - startTime);
                logService.log(keyId, null, model, model, 200, null, totalTokens > 0 ? totalTokens : null, latency, null, true, clientIp(httpReq), null);
                if (key != null && totalTokens > 0) keyService.incrementUsage(key.id(), totalTokens);
            } else {
                List<KiroEvent> events = KiroEventParser.parseStream(response.body());
                Map<String, Object> result = streamConverter.toOpenAiNonStreaming(events, model);
                httpRes.setContentType("application/json");
                mapper.writeValue(httpRes.getWriter(), result);

                int latency = (int) (System.currentTimeMillis() - startTime);
                logService.log(keyId, null, model, model, 200, null, null, latency, null, false, clientIp(httpReq), null);
            }
        } catch (Exception e) {
            log.error("[Completions] Error: {}", e.getMessage(), e);
            int latency = (int) (System.currentTimeMillis() - startTime);
            logService.log(keyId, null, model, model, 500, null, null, latency, null, stream, clientIp(httpReq), e.getMessage());
            if (!httpRes.isCommitted()) {
                httpRes.setStatus(500);
                httpRes.setContentType("application/json");
                httpRes.getWriter().write("{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\"}}");
            }
        }
    }

    private String resolveKeyId(Authentication auth) {
        if (auth != null && auth.getDetails() instanceof VirtualKey k) return k.id();
        return null;
    }

    private VirtualKey resolveKey(Authentication auth) {
        if (auth != null && auth.getDetails() instanceof VirtualKey k) return k;
        return null;
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        return Math.max(1, text.length() / 4);
    }
}
