package dev.suprim.gateway.utils;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import dev.suprim.gateway.api.request.CompletionsRequest;
import dev.suprim.gateway.proxy.Message;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Component
public class TokenEstimator {

    private static final double CLAUDE_CORRECTION_FACTOR = 1.15;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Encoding encoding;

    public TokenEstimator() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.round(encoding.countTokens(text) * CLAUDE_CORRECTION_FACTOR);
    }

    public int estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;

        for (Message msg : messages) {
            total += 4;
            Object content = msg.content();
            if (content instanceof String s) {
                total += encoding.countTokens(s);
            } else if (content instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> m) {
                        if ("text".equals(m.get("type")) && m.get("text") != null) {
                            total += encoding.countTokens(m.get("text").toString());
                        } else if ("image_url".equals(m.get("type"))) {
                            Object imageUrl = m.get("image_url");
                            if (imageUrl instanceof Map<?, ?> iuMap && iuMap.get("url") != null) {
                                String url = iuMap.get("url").toString();
                                int commaIdx = url.indexOf(',');
                                if (commaIdx > 0) {
                                    int dataLen = url.length() - commaIdx - 1;
                                    total += (int) Math.ceil(dataLen / 4.0);
                                } else {
                                    total += 100;
                                }
                            }
                        }
                    }
                }
            }
            List<Message.ToolCall> toolCalls = msg.toolCalls();
            if (toolCalls != null) {
                for (Message.ToolCall tc : toolCalls) {
                    total += 4;
                    Message.Function function = tc.function();
                    if (function != null) {
                        if (function.name() != null)
                            total += encoding.countTokens(function.name());
                        if (function.arguments() != null)
                            total += encoding.countTokens(function.arguments());
                    }
                }
            }
            if (msg.toolCallId() != null) {
                total += encoding.countTokens(msg.toolCallId());
            }
        }

        total += 3;
        return (int) Math.round(total * CLAUDE_CORRECTION_FACTOR);
    }

    public int estimateCompletionMessages(List<CompletionsRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;

        for (CompletionsRequest.Message msg : messages) {
            total += 4;
            Object content = msg.content();
            if (content != null) {
                total += encoding.countTokens(content.toString());
            }
            List<CompletionsRequest.ToolCall> toolCalls = msg.toolCalls();
            if (toolCalls != null) {
                for (CompletionsRequest.ToolCall tc : toolCalls) {
                    total += 4;
                    CompletionsRequest.FunctionCall function = tc.function();
                    if (function != null) {
                        if (function.name() != null)
                            total += encoding.countTokens(function.name());
                        if (function.arguments() != null)
                            total += encoding.countTokens(function.arguments());
                    }
                }
            }
            if (msg.toolCallId() != null) {
                total += encoding.countTokens(msg.toolCallId());
            }
        }

        total += 3;
        return (int) Math.round(total * CLAUDE_CORRECTION_FACTOR);
    }

    public int estimateTools(List<?> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        int total = 0;

        for (Object tool : tools) {
            total += 4;
            String json = MAPPER.writeValueAsString(tool);
            total += encoding.countTokens(json);
        }

        return (int) Math.round(total * CLAUDE_CORRECTION_FACTOR);
    }

    public int estimateRequest(List<Message> messages, List<?> tools) {
        return estimateMessages(messages) + estimateTools(tools);
    }
}
