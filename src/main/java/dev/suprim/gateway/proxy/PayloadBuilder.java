package dev.suprim.gateway.proxy;

import dev.suprim.gateway.auth.KiroAuthManager;
import dev.suprim.gateway.model.ModelResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class PayloadBuilder {

    private static final int MAX_PAYLOAD_BYTES = 600_000;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelResolver modelResolver;

    @SuppressWarnings("unchecked")
    public String buildOpenAiPayload(Map<String, Object> request, KiroAuthManager auth) throws Exception {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        String model = (String) request.get("model");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) request.get("tools");
        String modelId = modelResolver.resolve(model);

        String conversationId = UUID.randomUUID().toString();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode conversationState = root.putObject("conversationState");
        conversationState.put("chatTriggerType", "MANUAL");
        conversationState.put("conversationId", conversationId);

        String systemPrompt = null;
        int startIdx = 0;
        if (!messages.isEmpty() && "system".equals(messages.getFirst().get("role"))) {
            systemPrompt = ContentExtractor.fromMessage(messages.getFirst());
            startIdx = 1;
        }

        ArrayNode history = mapper.createArrayNode();
        String currentContent = "";
        List<Map<String, Object>> currentToolResults = null;

        for (int i = startIdx; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");

            if (i == messages.size() - 1 && "user".equals(role)) {
                currentContent = ContentExtractor.fromMessage(msg);
                continue;
            }

            if ("user".equals(role)) {
                ObjectNode entry = mapper.createObjectNode();
                ObjectNode userInput = entry.putObject("userInputMessage");
                userInput.put("content", ContentExtractor.fromMessage(msg));
                userInput.put("modelId", modelId);
                userInput.put("origin", "AI_EDITOR");
                history.add(entry);
            } else if ("assistant".equals(role)) {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("assistantResponseMessage", ContentExtractor.fromMessage(msg));
                history.add(entry);
            } else if ("tool".equals(role)) {
                if (currentToolResults == null) currentToolResults = new ArrayList<>();
                currentToolResults.add(msg);
            }
        }

        if (currentContent.isEmpty()) currentContent = "(empty placeholder)";
        if (systemPrompt != null && history.isEmpty()) {
            currentContent = systemPrompt + "\n\n" + currentContent;
        }

        ObjectNode currentMessage = conversationState.putObject("currentMessage");
        ObjectNode userInputMessage = currentMessage.putObject("userInputMessage");
        userInputMessage.put("content", currentContent);
        userInputMessage.put("modelId", modelId);
        userInputMessage.put("origin", "AI_EDITOR");

        if (tools != null && !tools.isEmpty()) {
            ObjectNode ctx = userInputMessage.putObject("userInputMessageContext");
            ArrayNode toolsNode = ctx.putArray("tools");
            for (Map<String, Object> tool : tools) {
                ObjectNode converted = ToolConverter.toKiroTool(tool, mapper);
                if (converted != null) toolsNode.add(converted);
            }
        }

        if (currentToolResults != null && !currentToolResults.isEmpty()) {
            ObjectNode ctx = userInputMessage.has("userInputMessageContext")
                    ? (ObjectNode) userInputMessage.get("userInputMessageContext")
                    : userInputMessage.putObject("userInputMessageContext");
            ArrayNode resultsNode = ctx.putArray("toolResults");
            for (Map<String, Object> tr : currentToolResults) {
                ObjectNode resultObj = mapper.createObjectNode();
                resultObj.put("toolUseId", (String) tr.get("tool_call_id"));
                resultObj.put("content", ContentExtractor.fromMessage(tr));
                resultsNode.add(resultObj);
            }
        }

        if (!history.isEmpty()) {
            if (systemPrompt != null && history.get(0).has("userInputMessage")) {
                ObjectNode first = (ObjectNode) history.get(0).get("userInputMessage");
                first.put("content", systemPrompt + "\n\n" + first.get("content").asText());
            }
            conversationState.set("history", history);
        }

        if (auth.getProfileArn() != null) {
            root.put("profileArn", auth.getProfileArn());
        }

        String json = mapper.writeValueAsString(root);
        while (json.length() > MAX_PAYLOAD_BYTES && !history.isEmpty()) {
            history.remove(0);
            if (history.isEmpty()) conversationState.remove("history");
            json = mapper.writeValueAsString(root);
        }

        return json;
    }

    public List<Map<String, Object>> convertResponsesInput(Object input) {
        return ResponsesInputConverter.convert(input);
    }
}
