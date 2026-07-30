package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import lombok.Builder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts client messages into alternating Kiro history and one current user turn.
 */
final class HistoryBuilder {

	private static final JsonMapper MAPPER = new JsonMapper();

	private HistoryBuilder() {}

	@Builder
	record HistoryResult(ArrayNode history, ObjectNode currentMessage) {}

	static HistoryResult build(
			List<Message> messages,
			String modelId,
			boolean toolsEnabled
	) {
		ArrayNode entries = MAPPER.createArrayNode();
		Set<String> pendingToolUseIds = new HashSet<>();
		Set<String> consumedToolUseIds = new HashSet<>();

		for (Message message : messages) {
			switch (message.role()) {
				case "user" -> {
					pendingToolUseIds.clear();
					appendMerged(
							entries,
							UserEntryBuilder.build(message, modelId)
					);
				}
				case "assistant" -> {
					pendingToolUseIds.clear();
					ObjectNode entry = AssistantEntryBuilder.build(
							message,
							toolsEnabled
					);
					collectToolUseIds(entry, pendingToolUseIds);
					appendMerged(entries, entry);
				}
				case "tool" -> {
					String toolUseId = message.toolCallId();
					if (toolsEnabled && toolUseId != null &&
					    pendingToolUseIds.contains(toolUseId) &&
					    consumedToolUseIds.add(toolUseId)) {
						appendMerged(
								entries,
								ToolResultEntryBuilder.build(
										List.of(message),
										modelId
								)
						);
					} else {
						appendMerged(
								entries, UserEntryBuilder.build(
										Message.of(
												"user",
												toolResultText(message)
										),
										modelId
								)
						);
					}
				}
			}
		}

		ObjectNode currentMessage = currentMessage(entries, modelId);
		return HistoryResult.builder()
		                    .history(entries)
		                    .currentMessage(currentMessage)
		                    .build();
	}

	private static ObjectNode currentMessage(
			ArrayNode entries,
			String modelId
	) {
		if (!entries.isEmpty() && entries.get(entries.size() - 1).has(
				"userInputMessage")) {
			ObjectNode entry = (ObjectNode) entries.remove(entries.size() - 1);
			return ((ObjectNode) entry.get("userInputMessage")).deepCopy();
		}
		return (ObjectNode) UserEntryBuilder.build(
				Message.of("user", "."), modelId
		).get("userInputMessage");
	}

	private static void appendMerged(ArrayNode entries, ObjectNode entry) {
		if (entries.isEmpty()) {
			entries.add(entry);
			return;
		}

		ObjectNode previous = (ObjectNode) entries.get(entries.size() - 1);
		if (entry.has("userInputMessage") && previous.has("userInputMessage")) {
			mergeUser(
					(ObjectNode) previous.get("userInputMessage"),
					(ObjectNode) entry.get("userInputMessage")
			);
		} else if (entry.has("assistantResponseMessage") &&
		           previous.has("assistantResponseMessage")) {
			mergeAssistant(
					(ObjectNode) previous.get("assistantResponseMessage"),
					(ObjectNode) entry.get("assistantResponseMessage")
			);
		} else {
			entries.add(entry);
		}
	}

	private static void mergeUser(ObjectNode target, ObjectNode source) {
		target.put("content", joinContent(target, source));
		appendArray(target, source, "images");
		ObjectNode sourceContext = source.has("userInputMessageContext")
				? (ObjectNode) source.get("userInputMessageContext")
				: null;
		if (sourceContext == null) {
			return;
		}

		ObjectNode targetContext = target.has("userInputMessageContext")
				? (ObjectNode) target.get("userInputMessageContext")
				: target.putObject("userInputMessageContext");
		appendArray(targetContext, sourceContext, "toolResults");
	}

	private static void mergeAssistant(ObjectNode target, ObjectNode source) {
		target.put("content", joinContent(target, source));
		appendArray(target, source, "toolUses");
	}

	private static String joinContent(ObjectNode first, ObjectNode second) {
		String left = first.path("content").asString().trim();
		String right = second.path("content").asString().trim();
		if (left.isEmpty()) {
			return right;
		}
		if (right.isEmpty()) {
			return left;
		}
		return left + "\n\n" + right;
	}

	private static void appendArray(
			ObjectNode target,
			ObjectNode source,
			String field
	) {
		JsonNode sourceArray = source.get(field);
		if (sourceArray == null || !sourceArray.isArray()) {
			return;
		}
		ArrayNode targetArray = target.has(field)
				? (ArrayNode) target.get(field)
				: target.putArray(field);
		targetArray.addAll((ArrayNode) sourceArray);
	}

	private static void collectToolUseIds(
			ObjectNode entry,
			Set<String> toolUseIds
	) {
		JsonNode uses = entry.at("/assistantResponseMessage/toolUses");
		if (!uses.isArray()) {
			return;
		}
		for (JsonNode use : uses) {
			String id = use.path("toolUseId").asString();
			if (!id.isBlank()) toolUseIds.add(id);
		}
	}

	private static String toolResultText(Message message) {
		String content = ContentExtractor.fromMessage(message);
		return "[Tool result: " + (content == null ? "" : content) + "]";
	}
}
