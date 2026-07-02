package dev.kiro.gateway.proxy;

import dev.kiro.gateway.proxy.KiroEventParser.KiroEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StreamConverter {

    private final ObjectMapper mapper = new ObjectMapper();

    public String toOpenAiChunk(KiroEvent event, String model, String id) throws Exception {
        if ("content".equals(event.type())) {
            Map<String, Object> chunk = Map.of(
                    "id", id,
                    "object", "chat.completion.chunk",
                    "created", System.currentTimeMillis() / 1000,
                    "model", model,
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of("content", event.content()),
                            "finish_reason", ""
                    ))
            );
            return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
        }

        if ("tool_use".equals(event.type()) && event.toolStop()) {
            String toolId = event.toolUseId() != null ? event.toolUseId() : "call_" + UUID.randomUUID();
            Map<String, Object> chunk = Map.of(
                    "id", id,
                    "object", "chat.completion.chunk",
                    "created", System.currentTimeMillis() / 1000,
                    "model", model,
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of("tool_calls", List.of(Map.of(
                                    "index", 0,
                                    "id", toolId,
                                    "type", "function",
                                    "function", Map.of("name", event.toolName(), "arguments", event.toolInput())
                            ))),
                            "finish_reason", ""
                    ))
            );
            return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
        }

        return null;
    }

    public String toOpenAiDone() {
        return "data: [DONE]\n\n";
    }

    public String toOpenAiStopChunk(String model, String id) throws Exception {
        Map<String, Object> chunk = Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", System.currentTimeMillis() / 1000,
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "delta", Map.of(),
                        "finish_reason", "stop"
                ))
        );
        return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
    }

    public Map<String, Object> toOpenAiNonStreaming(List<KiroEvent> events, String model) {
        StringBuilder contentBuilder = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (KiroEvent event : events) {
            if ("content".equals(event.type()) && event.content() != null) {
                contentBuilder.append(event.content());
            } else if ("tool_use".equals(event.type()) && event.toolStop()) {
                String toolId = event.toolUseId() != null ? event.toolUseId() : "call_" + UUID.randomUUID();
                toolCalls.add(Map.of(
                        "id", toolId,
                        "type", "function",
                        "function", Map.of("name", event.toolName(), "arguments", event.toolInput())
                ));
            }
        }

        HashMap<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", contentBuilder.toString());
        if (!toolCalls.isEmpty()) message.put("tool_calls", toolCalls);

        String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";

        return Map.of(
                "id", "chatcmpl-" + UUID.randomUUID(),
                "object", "chat.completion",
                "created", System.currentTimeMillis() / 1000,
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", message,
                        "finish_reason", finishReason
                )),
                "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0, "total_tokens", 0)
        );
    }

    String toAnthropicEvent(String eventType, Object data) throws Exception {
        return "event: " + eventType + "\ndata: " + mapper.writeValueAsString(data) + "\n\n";
    }
}
