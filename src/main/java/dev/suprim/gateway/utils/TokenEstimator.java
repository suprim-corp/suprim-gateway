package dev.suprim.gateway.utils;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TokenEstimator {

    private static final double CLAUDE_CORRECTION_FACTOR = 1.15;
    private final Encoding encoding;

    public TokenEstimator() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.round(encoding.countTokens(text) * CLAUDE_CORRECTION_FACTOR);
    }

    @SuppressWarnings("unchecked")
    public int estimateMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;

        for (Map<String, Object> msg : messages) {
            total += 4;
            Object content = msg.get("content");
            if (content instanceof String s) {
                total += encoding.countTokens(s);
            } else if (content instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> m) {
                        if ("text".equals(m.get("type")) && m.get("text") != null) {
                            total += encoding.countTokens(m.get("text").toString());
                        } else if ("image_url".equals(m.get("type"))) {
                            Map<String, Object> imageUrl = (Map<String, Object>) m.get("image_url");
                            if (imageUrl != null && imageUrl.get("url") != null) {
                                String url = imageUrl.get("url").toString();
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
            Object toolCalls = msg.get("tool_calls");
            if (toolCalls instanceof List<?> calls) {
                for (Object call : calls) {
                    if (call instanceof Map<?, ?> tc) {
                        total += 4;
                        Map<String, Object> function = (Map<String, Object>) tc.get("function");
                        if (function != null) {
                            if (function.get("name") != null)
                                total += encoding.countTokens(function.get("name").toString());
                            if (function.get("arguments") != null)
                                total += encoding.countTokens(function.get("arguments").toString());
                        }
                    }
                }
            }
            if (msg.get("tool_call_id") != null) {
                total += encoding.countTokens(msg.get("tool_call_id").toString());
            }
        }

        total += 3;
        return (int) Math.round(total * CLAUDE_CORRECTION_FACTOR);
    }

    @SuppressWarnings("unchecked")
    public int estimateTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        int total = 0;

        for (Map<String, Object> tool : tools) {
            total += 4;
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            Map<String, Object> payload = function != null ? function : tool;
            if (payload.get("name") != null)
                total += encoding.countTokens(payload.get("name").toString());
            if (payload.get("description") != null)
                total += encoding.countTokens(payload.get("description").toString());
            Object params = payload.get("input_schema");
            if (params == null) params = payload.get("parameters");
            if (params != null) {
                total += encoding.countTokens(params.toString());
            }
        }

        return (int) Math.round(total * CLAUDE_CORRECTION_FACTOR);
    }

    @SuppressWarnings("unchecked")
    public int estimateRequest(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return estimateMessages(messages) + estimateTools(tools);
    }
}
