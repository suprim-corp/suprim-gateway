package dev.suprim.gateway.proxy;

import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.model.ModelResolver;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final int MAX_PAYLOAD_BYTES = 900_000;
	private static final int MAX_TOOL_RESULT_CONTENT_LEN = 4000;
	private static final Logger log = LoggerFactory.getLogger(PayloadBuilder.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final ModelResolver modelResolver;

	@SuppressWarnings("unchecked")
	public String buildOpenAiPayload(
			Map<String, Object> request,
			KiroAuthManager auth
	) throws Exception {
		List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get(
				"messages");
		String model = (String) request.get("model");
		List<Map<String, Object>> tools = (List<Map<String, Object>>) request.get(
				"tools");
		String modelId = modelResolver.resolve(model);

		// Separate system messages from conversation
		StringBuilder systemPrompt = new StringBuilder();
		List<Map<String, Object>> nonSystemMessages = new ArrayList<>();

		for (Map<String, Object> msg : messages) {
			String role = (String) msg.get("role");
			if ("system".equals(role)) {
				String text = ContentExtractor.fromMessage(msg);
				if (text != null && !text.isEmpty()) {
					if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
					systemPrompt.append(text);
				}
			} else {
				nonSystemMessages.add(msg);
			}
		}

		// Build history
		ArrayNode history = mapper.createArrayNode();
		String currentContent = "";
		List<Map<String, Object>> currentToolResults = null;

		// System prompt → priming pair at history start
		if (!systemPrompt.isEmpty()) {
			ObjectNode primingUser = mapper.createObjectNode();
			ObjectNode primingUserMsg = primingUser.putObject("userInputMessage");
			primingUserMsg.put("content", systemPrompt.toString().trim());
			primingUserMsg.put("modelId", modelId);
			primingUserMsg.put("origin", "AI_EDITOR");
			history.add(primingUser);

			ObjectNode primingAssistant = mapper.createObjectNode();
			ObjectNode primingAssistantMsg = primingAssistant.putObject(
					"assistantResponseMessage");
			primingAssistantMsg.put(
					"content",
					"I will follow these instructions."
			);
			history.add(primingAssistant);
		}

		List<ContentExtractor.KiroImage> currentImages = List.of();

		for (int i = 0; i < nonSystemMessages.size(); i++) {
			Map<String, Object> msg = nonSystemMessages.get(i);
			String role = (String) msg.get("role");
			boolean isLast = (i == nonSystemMessages.size() - 1);

			if ("user".equals(role)) {
				String content = ContentExtractor.fromMessage(msg);
				List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(
						msg);
				if (isLast) {
					currentContent = content != null ? content : "";
					currentImages = images;
				} else {
					ObjectNode entry = mapper.createObjectNode();
					ObjectNode userMsg = entry.putObject("userInputMessage");
					userMsg.put(
							"content",
							content != null &&
							!content.isEmpty() ? content : "."
					);
					userMsg.put("modelId", modelId);
					userMsg.put("origin", "AI_EDITOR");
					appendImages(userMsg, images);
					history.add(entry);
				}
			} else if ("assistant".equals(role)) {
				String content = ContentExtractor.fromMessage(msg);
				ObjectNode entry = mapper.createObjectNode();
				ObjectNode assistantMsg = entry.putObject(
						"assistantResponseMessage");
				assistantMsg.put("content", content != null ? content : "");

				// Preserve tool_calls as toolUses
				Object toolCallsObj = msg.get("tool_calls");
				if (toolCallsObj instanceof List<?> toolCalls &&
				    !toolCalls.isEmpty()) {
					ArrayNode toolUsesNode = assistantMsg.putArray("toolUses");
					for (Object tc : toolCalls) {
						if (!(tc instanceof Map<?, ?> tcMap)) continue;
						ObjectNode tuNode = toolUsesNode.addObject();
						tuNode.put("toolUseId", str(tcMap.get("id")));
						tuNode.put("name", extractToolName(tcMap));
						tuNode.set("input", parseToolInput(tcMap));
					}
				}
				history.add(entry);
			} else if ("tool".equals(role)) {
				if (currentToolResults == null)
					currentToolResults = new ArrayList<>();
				currentToolResults.add(msg);

				// Check if next message is also tool — if not, flush tool results
				int nextIdx = i + 1;
				boolean nextIsTool = nextIdx < nonSystemMessages.size()
				                     && "tool".equals(nonSystemMessages.get(
						nextIdx).get("role"));

				if (!nextIsTool && !isLast) {
					ObjectNode entry = mapper.createObjectNode();
					ObjectNode userMsg = entry.putObject("userInputMessage");
					userMsg.put("content", ".");
					userMsg.put("modelId", modelId);
					userMsg.put("origin", "AI_EDITOR");
					ObjectNode ctx = userMsg.putObject("userInputMessageContext");
					ArrayNode resultsNode = ctx.putArray("toolResults");
					for (Map<String, Object> tr : currentToolResults) {
						ObjectNode resultObj = resultsNode.addObject();
						resultObj.put("toolUseId", str(tr.get("tool_call_id")));
						ArrayNode contentArr = resultObj.putArray("content");
						ObjectNode textObj = contentArr.addObject();
						textObj.put(
								"text",
								truncate(
										ContentExtractor.fromMessage(tr),
										MAX_TOOL_RESULT_CONTENT_LEN
								)
						);
						resultObj.put("status", "success");
					}
					history.add(entry);
					currentToolResults = null;
				}
			}
		}

		// Current message content
		if (currentContent.isEmpty()) {
			if (currentToolResults != null && !currentToolResults.isEmpty()) {
				currentContent = buildToolResultsContinuation(currentToolResults);
			} else if (!currentImages.isEmpty()) {
				currentContent = ".";
			} else {
				currentContent = ".";
			}
		}

		// Build payload
		ObjectNode root = mapper.createObjectNode();
		ObjectNode conversationState = root.putObject("conversationState");
		conversationState.put("chatTriggerType", "MANUAL");
		conversationState.put("conversationId", UUID.randomUUID().toString());

		ObjectNode currentMessage = conversationState.putObject("currentMessage");
		ObjectNode userInputMessage = currentMessage.putObject(
				"userInputMessage");
		userInputMessage.put("content", currentContent);
		userInputMessage.put("modelId", modelId);
		userInputMessage.put("origin", "AI_EDITOR");
		appendImages(userInputMessage, currentImages);

		// Attach tools and current tool results to currentMessage context
		boolean hasTools = tools != null && !tools.isEmpty();
		boolean hasCurrentToolResults =
				currentToolResults != null && !currentToolResults.isEmpty();

		if (hasTools || hasCurrentToolResults) {
			ObjectNode ctx = userInputMessage.putObject(
					"userInputMessageContext");
			if (hasTools) {
				ArrayNode toolsNode = ctx.putArray("tools");
				for (Map<String, Object> tool : tools) {
					ObjectNode converted = ToolConverter.toKiroTool(
							tool,
							mapper
					);
					if (converted != null) toolsNode.add(converted);
				}
			}
			if (hasCurrentToolResults) {
				ArrayNode resultsNode = ctx.putArray("toolResults");
				for (Map<String, Object> tr : currentToolResults) {
					ObjectNode resultObj = resultsNode.addObject();
					resultObj.put("toolUseId", str(tr.get("tool_call_id")));
					ArrayNode contentArr = resultObj.putArray("content");
					ObjectNode textObj = contentArr.addObject();
					textObj.put(
							"text",
							truncate(
									ContentExtractor.fromMessage(tr),
									MAX_TOOL_RESULT_CONTENT_LEN
							)
					);
					resultObj.put("status", "success");
				}
			}
		}

		if (history.size() > 0) {
			conversationState.set("history", history);
		}

		if (auth.getProfileArn() != null) {
			root.put("profileArn", auth.getProfileArn());
		}

		// Truncate history if payload too large
		String json = mapper.writeValueAsString(root);
		log.debug(
				"[Payload] size={}, history={}, hasTools={}, hasToolResults={}",
				json.length(),
				history.size(),
				hasTools,
				hasCurrentToolResults
		);
		while (json.length() > MAX_PAYLOAD_BYTES && history.size() > 2) {
			// Keep priming pair (first 2 if system prompt exists), drop oldest after that
			int removeIdx =
					!systemPrompt.isEmpty() && history.size() > 2 ? 2 : 0;
			history.remove(removeIdx);
			if (history.isEmpty()) conversationState.remove("history");
			json = mapper.writeValueAsString(root);
		}

		return json;
	}

	public List<Map<String, Object>> convertResponsesInput(Object input) {
		return ResponsesInputConverter.convert(input);
	}

	private String buildToolResultsContinuation(List<Map<String, Object>> toolResults) {
		StringBuilder sb = new StringBuilder("Tool results:\n\n");
		for (Map<String, Object> tr : toolResults) {
			String content = ContentExtractor.fromMessage(tr);
			if (content != null && !content.isEmpty()) {
				sb.append(content).append("\n\n");
			}
		}
		return truncate(sb.toString().trim(), MAX_TOOL_RESULT_CONTENT_LEN);
	}

	private String extractToolName(Map<?, ?> tcMap) {
		Object fn = tcMap.get("function");
		if (fn instanceof Map<?, ?> fnMap) {
			Object name = fnMap.get("name");
			return name != null ? name.toString() : "";
		}
		return "";
	}

	private ObjectNode parseToolInput(Map<?, ?> tcMap) {
		Object fn = tcMap.get("function");
		if (fn instanceof Map<?, ?> fnMap) {
			Object args = fnMap.get("arguments");
			if (args instanceof String argsStr) {
				try {
					return (ObjectNode) mapper.readTree(argsStr);
				} catch (Exception e) {
					ObjectNode fallback = mapper.createObjectNode();
					fallback.put("input", argsStr);
					return fallback;
				}
			}
		}
		return mapper.createObjectNode();
	}

	private void appendImages(
			ObjectNode userMsg,
			List<ContentExtractor.KiroImage> images
	) {
		if (images == null || images.isEmpty()) return;
		ArrayNode imagesNode = userMsg.putArray("images");
		for (ContentExtractor.KiroImage img : images) {
			ObjectNode imgNode = imagesNode.addObject();
			imgNode.put("format", img.format());
			ObjectNode source = imgNode.putObject("source");
			source.put("bytes", img.bytes());
		}
	}

	private static String str(Object obj) {
		return obj != null ? obj.toString() : "";
	}

	private static String truncate(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max);
	}
}
