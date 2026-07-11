package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import lombok.Builder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts OpenAI-style messages into Kiro conversation history format.
 *
 * <p>The last user message is extracted as "current message" (not placed in history),
 * while all preceding messages become history entries. Tool results are batched
 * until a non-tool message follows.</p>
 */
final class HistoryBuilder {

	private static final JsonMapper MAPPER = new JsonMapper();

	private HistoryBuilder() {}

	/**
	 * Result of building history: the history array, the current message content,
	 * any images attached to the current message, and any pending tool results.
	 */
	@Builder
	record HistoryResult(
			ArrayNode history,
			String currentContent,
			List<ContentExtractor.KiroImage> currentImages,
			List<Message> currentToolResults
	) {}

	/**
	 * Builds Kiro history from non-system messages.
	 *
	 * @param nonSystemMessages messages with system messages already removed
	 * @param modelId           resolved Kiro model identifier
	 * @return history array + current message state
	 */
	static HistoryResult build(
			List<Message> nonSystemMessages,
			String modelId
	) {
		Context ctx = new Context(modelId);

		for (int i = 0; i < nonSystemMessages.size(); i++) {
			Message msg = nonSystemMessages.get(i);
			boolean isLast = (i == nonSystemMessages.size() - 1);
			boolean nextIsTool = i + 1 < nonSystemMessages.size()
			                     && "tool".equals(
					nonSystemMessages.get(i + 1)
					                 .role()
			);

			switch (msg.role()) {
				case "user" -> ctx.handleUser(msg, isLast);
				case "assistant" -> ctx.history.add(
						AssistantEntryBuilder.build(msg)
				);
				case "tool" -> ctx.handleTool(msg, isLast, nextIsTool);
			}
		}

		return HistoryResult.builder()
		                    .history(ctx.history)
		                    .currentContent(ctx.currentContent)
		                    .currentImages(ctx.currentImages)
		                    .currentToolResults(ctx.currentToolResults)
		                    .build();
	}

	/**
	 * Prepends a system prompt as a priming user/assistant pair at the start of history.
	 */
	static void addSystemPriming(
			ArrayNode history,
			String systemPrompt,
			String modelId
	) {
		ObjectNode primingUser = MAPPER.createObjectNode();
		ObjectNode primingUserMsg = primingUser.putObject("userInputMessage");
		primingUserMsg.put("content", systemPrompt);
		primingUserMsg.put("modelId", modelId);
		primingUserMsg.put("origin", "AI_EDITOR");
		history.add(primingUser);

		ObjectNode primingAssistant = MAPPER.createObjectNode();
		ObjectNode primingAssistantMsg = primingAssistant.putObject(
				"assistantResponseMessage"
		);
		primingAssistantMsg.put("content", "I will follow these instructions.");
		history.add(primingAssistant);
	}

	private static class Context {
		final ArrayNode history = MAPPER.createArrayNode();
		final String modelId;
		String currentContent = "";
		List<ContentExtractor.KiroImage> currentImages = List.of();
		List<Message> currentToolResults;

		Context(String modelId) {
			this.modelId = modelId;
		}

		void handleUser(Message msg, boolean isLast) {
			if (isLast) {
				currentContent = ContentExtractor.fromMessage(msg);
				if (currentContent == null) {
					currentContent = "";
				}
				currentImages = ContentExtractor.extractImages(msg);
			} else {
				history.add(UserEntryBuilder.build(msg, modelId));
			}
		}

		void handleTool(Message msg, boolean isLast, boolean nextIsTool) {
			if (currentToolResults == null) {
				currentToolResults = new ArrayList<>();
			}

			currentToolResults.add(msg);

			if (!nextIsTool && !isLast) {
				history.add(
						ToolResultEntryBuilder.build(
								currentToolResults,
								modelId
						)
				);
				currentToolResults = null;
			}
		}
	}
}
