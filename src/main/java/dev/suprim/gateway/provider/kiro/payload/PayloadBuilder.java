package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.kiro.utils.ToolConverter;
import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Tool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class PayloadBuilder {

	private static final int MAX_PAYLOAD_BYTES = 900_000;
	private static final Logger log = LoggerFactory.getLogger(PayloadBuilder.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final ModelResolver modelResolver;

	public String buildOpenAiPayload(
			InternalRequest request,
			KiroAuthManager auth
	) throws Exception {
		List<Message> messages = request.messages() != null
				? new ArrayList<>(request.messages())
				: new ArrayList<>();
		String model = request.model();
		List<Tool> tools = request.tools();

		return buildKiroPayload(messages, model, tools, auth);
	}

	private String buildKiroPayload(
			List<Message> messages,
			String model,
			List<Tool> tools,
			KiroAuthManager auth
	) throws Exception {
		String modelId = modelResolver.resolve(model);

		String systemPrompt = extractSystemPrompt(messages);
		List<Message> nonSystemMessages = filterNonSystem(messages);

		HistoryBuilder.HistoryResult historyResult = HistoryBuilder.build(
				nonSystemMessages,
				modelId
		);
		ArrayNode history = historyResult.history();

		if (!systemPrompt.isEmpty()) {
			HistoryBuilder.addSystemPriming(history, systemPrompt, modelId);
			ArrayNode reordered = mapper.createArrayNode();
			reordered.add(history.get(history.size() - 2));
			reordered.add(history.get(history.size() - 1));
			for (int i = 0; i < history.size() - 2; i++) {
				reordered.add(history.get(i));
			}
			history = reordered;
		}

		if (history.size() > 2) {
			log.debug("[Payload] history[0] keys: {}", history.get(0).propertyNames());
			log.debug("[Payload] history[1] keys: {}", history.get(1).propertyNames());
			JsonNode entry2 = history.get(2);
			log.debug("[Payload] history[2] keys: {}", entry2.propertyNames());
			JsonNode userMsg2 = entry2.get("userInputMessage");
			if (userMsg2 != null) {
				String content2 = userMsg2.has("content") ? userMsg2.get("content").asString() : "null";
				log.debug("[Payload] history[2] content (first 100): {}", content2.length() > 100 ? content2.substring(0, 100) : content2);
				JsonNode ctx2 = userMsg2.get("userInputMessageContext");
				if (ctx2 != null) {
					log.debug("[Payload] history[2] ctx keys: {}", ctx2.propertyNames());
					if (ctx2.has("toolResults")) {
						log.debug("[Payload] history[2] toolResults count: {}", ctx2.get("toolResults").size());
					}
				}
			}
		}

		String currentContent = resolveCurrentContent(
				historyResult.currentContent(),
				historyResult.currentImages(),
				historyResult.currentToolResults()
		);

		ObjectNode root = buildRoot(
				history, modelId, currentContent,
				historyResult.currentImages(),
				historyResult.currentToolResults(),
				tools, auth, systemPrompt
		);

		return truncatePayload(root, history, systemPrompt);
	}

	private ObjectNode buildRoot(
			ArrayNode history,
			String modelId,
			String currentContent,
			List<ContentExtractor.KiroImage> currentImages,
			List<Message> currentToolResults,
			List<Tool> tools,
			KiroAuthManager auth,
			String systemPrompt
	) {
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
		ImageAppender.append(userInputMessage, currentImages);

		attachContext(userInputMessage, tools, currentToolResults, history);

		if (!history.isEmpty()) {
			conversationState.set("history", history);
		}

		if (auth.getProfileArn() != null) {
			root.put("profileArn", auth.getProfileArn());
		}

		return root;
	}

	private void attachContext(
			ObjectNode userInputMessage,
			List<Tool> tools,
			List<Message> currentToolResults,
			ArrayNode history
	) {
		boolean hasTools = tools != null && !tools.isEmpty();
		boolean hasToolResults =
				currentToolResults != null && !currentToolResults.isEmpty();

		if (!hasTools && !hasToolResults) return;

		ObjectNode ctx = userInputMessage.putObject("userInputMessageContext");
		if (hasTools) {
			ArrayNode toolsArr = ctx.putArray("tools");
			for (JsonNode kiroToolNode : mapper.valueToTree(ToolConverter.convert(
					tools))) {
				toolsArr.add(kiroToolNode);
			}
		}
		if (hasToolResults) {
			int allowedCount = countLastAssistantToolUses(history);
			log.debug(
					"[Payload] toolResults={}, allowedByHistory={}",
					currentToolResults.size(),
					allowedCount
			);
			if (allowedCount > 0) {
				ArrayNode resultsNode = ctx.putArray("toolResults");
				int count = Math.min(
						currentToolResults.size(),
						allowedCount
				);
				for (int i = 0; i < count; i++) {
					ToolResultAppender.appendResult(
							resultsNode,
							currentToolResults.get(i)
					);
				}
			}
		}
	}

	private String truncatePayload(
			ObjectNode root,
			ArrayNode history,
			String systemPrompt
	) throws Exception {
		ObjectNode conversationState = (ObjectNode) root.get("conversationState");
		String json = mapper.writeValueAsString(root);

		boolean hasTools = root.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/tools") !=
		                   null;
		boolean hasToolResults = root.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/toolResults") !=
		                         null;

		log.debug(
				"[Payload] size={}, history={}, hasTools={}, hasToolResults={}",
				json.length(), history.size(), hasTools, hasToolResults
		);

		validateToolUseMismatch(history);

		while (json.length() > MAX_PAYLOAD_BYTES && history.size() > 2) {
			int removeIdx =
					!systemPrompt.isEmpty() && history.size() > 2 ? 2 : 0;
			history.remove(removeIdx);
			if (history.isEmpty()) conversationState.remove("history");
			json = mapper.writeValueAsString(root);
		}

		fixToolResultMismatches(history);
		json = mapper.writeValueAsString(root);

		return json;
	}

	private static String extractSystemPrompt(List<Message> messages) {
		StringBuilder systemPrompt = new StringBuilder();
		for (Message msg : messages) {
			if ("system".equals(msg.role())) {
				String text = ContentExtractor.fromMessage(msg);
				if (text != null && !text.isEmpty()) {
					if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
					systemPrompt.append(text);
				}
			}
		}
		return systemPrompt.toString().trim();
	}

	private static List<Message> filterNonSystem(List<Message> messages) {
		List<Message> result = new ArrayList<>();
		for (Message msg : messages) {
			if (!"system".equals(msg.role())) {
				result.add(msg);
			}
		}
		return result;
	}

	private static String resolveCurrentContent(
			String currentContent,
			List<ContentExtractor.KiroImage> currentImages,
			List<Message> currentToolResults
	) {
		if (!currentContent.isEmpty()) return currentContent;

		if (currentToolResults != null && !currentToolResults.isEmpty()) {
			StringBuilder sb = new StringBuilder("Tool results:\n\n");
			for (Message tr : currentToolResults) {
				String content = ContentExtractor.fromMessage(tr);
				if (content != null && !content.isEmpty()) {
					sb.append(content).append("\n\n");
				}
			}
			return ToolResultAppender.truncate(sb.toString().trim());
		}

		return ".";
	}

	private static int countLastAssistantToolUses(ArrayNode history) {
		for (int i = history.size() - 1; i >= 0; i--) {
			JsonNode entry = history.get(i);
			JsonNode assistantMsg = entry.get("assistantResponseMessage");
			if (assistantMsg != null) {
				JsonNode toolUses = assistantMsg.get("toolUses");
				return toolUses != null ? toolUses.size() : 0;
			}
		}
		return 0;
	}

	private void fixToolResultMismatches(ArrayNode history) {
		int lastToolUseCount = 0;
		for (int i = 0; i < history.size(); i++) {
			JsonNode entry = history.get(i);
			JsonNode assistantMsg = entry.get("assistantResponseMessage");
			if (assistantMsg != null) {
				JsonNode toolUses = assistantMsg.get("toolUses");
				lastToolUseCount = toolUses != null ? toolUses.size() : 0;
				continue;
			}
			JsonNode userMsg = entry.get("userInputMessage");
			if (userMsg != null) {
				JsonNode ctx = userMsg.get("userInputMessageContext");
				if (ctx != null && ctx.has("toolResults")) {
					JsonNode toolResults = ctx.get("toolResults");
					if (toolResults != null && toolResults.size() > lastToolUseCount) {
						log.warn(
								"[Payload] Fixing mismatch at history[{}]: toolResults={} > toolUses={}",
								i, toolResults.size(), lastToolUseCount
						);
						if (lastToolUseCount == 0) {
							((ObjectNode) ctx).remove("toolResults");
						} else {
							ArrayNode capped = mapper.createArrayNode();
							for (int j = 0; j < lastToolUseCount; j++) {
								capped.add(toolResults.get(j));
							}
							((ObjectNode) ctx).set("toolResults", capped);
						}
					}
				}
				// Check content for "Tool results:" pattern that Bedrock might interpret
				JsonNode contentNode = userMsg.get("content");
				if (contentNode != null && lastToolUseCount == 0) {
					String content = contentNode.asString();
					if (content != null && content.startsWith("Tool results:")) {
						log.warn("[Payload] Clearing 'Tool results:' content at history[{}] (no preceding toolUses)", i);
						((ObjectNode) userMsg).put("content", ".");
					}
				}
				lastToolUseCount = 0;
			}
		}
	}

	private void validateToolUseMismatch(ArrayNode history) {
		int lastToolUseCount = 0;
		for (int i = 0; i < history.size(); i++) {
			JsonNode entry = history.get(i);
			JsonNode assistantMsg = entry.get("assistantResponseMessage");
			if (assistantMsg != null) {
				JsonNode toolUses = assistantMsg.get("toolUses");
				lastToolUseCount = toolUses != null ? toolUses.size() : 0;
				continue;
			}
			JsonNode userMsg = entry.get("userInputMessage");
			if (userMsg != null) {
				JsonNode ctx = userMsg.get("userInputMessageContext");
				if (ctx != null) {
					JsonNode toolResults = ctx.get("toolResults");
					if (toolResults != null && toolResults.size() > lastToolUseCount) {
						log.warn(
								"[Payload] MISMATCH at history[{}]: toolResults={} > previousToolUses={}",
								i, toolResults.size(), lastToolUseCount
						);
					}
				}
				lastToolUseCount = 0;
			}
		}
	}
}
