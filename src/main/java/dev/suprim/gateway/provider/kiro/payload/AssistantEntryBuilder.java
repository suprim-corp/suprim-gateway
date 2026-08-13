package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

final class AssistantEntryBuilder {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AssistantEntryBuilder() {}

	static ObjectNode build(Message msg, boolean toolsEnabled) {
		ObjectNode entry = MAPPER.createObjectNode();
		ObjectNode assistantMsg = entry.putObject("assistantResponseMessage");
		String content = ContentExtractor.fromMessage(msg);
		assistantMsg.put("content", content != null ? content : "");

		List<Message.ToolCall> toolCalls = msg.toolCalls();
		if (toolCalls != null && !toolCalls.isEmpty()) {
			if (!toolsEnabled) {
				assistantMsg.put("content", appendToolCallText(content, toolCalls));
				return entry;
			}
			ArrayNode toolUsesNode = assistantMsg.putArray("toolUses");
			for (Message.ToolCall toolCall : toolCalls) {
				if (toolCall.id() != null && !toolCall.id().isBlank()) {
					appendToolUse(toolUsesNode, toolCall);
				}
			}
			if (toolUsesNode.isEmpty()) assistantMsg.remove("toolUses");
		}
		if (assistantMsg.path("content").asString().isBlank()) {
			assistantMsg.put("content", ContentPlaceholder.ASSISTANT);
		}
		return entry;
	}

	private static String appendToolCallText(
			String content,
			List<Message.ToolCall> toolCalls
	) {
		StringBuilder text = new StringBuilder(content == null ? "" : content);
		for (Message.ToolCall toolCall : toolCalls) {
			Message.Function function = toolCall.function();
			String name = function == null || function.name() == null
					? "unknown"
					: function.name();
			String input = function == null || function.arguments() == null
					? "{}"
					: function.arguments();
			appendLine(text, toolCallText(name, input));
		}
		return text.toString();
	}

	/**
	 * Renders a tool call as prose, for the paths where it cannot survive as a structured
	 * {@code toolUses} entry.
	 */
	static String toolCallText(String name, String input) {
		return "[Tool call: " + name + ' ' + input + ']';
	}

	static void appendLine(StringBuilder text, String line) {
		if (!text.isEmpty()) {
			text.append('\n');
		}
		text.append(line);
	}

	private static void appendToolUse(
			ArrayNode toolUsesNode,
			Message.ToolCall tc
	) {
		ObjectNode tuNode = toolUsesNode.addObject();
		tuNode.put("toolUseId", Optional.ofNullable(tc.id()).orElse(""));
		Message.Function fn = tc.function();

		tuNode.put(
				"name",
				fn != null ? Optional.ofNullable(fn.name()).orElse("") : ""
		);

		if (fn != null && fn.arguments() != null) {
			try {
				tuNode.set("input", MAPPER.readTree(fn.arguments()));
			} catch (Exception e) {
				ObjectNode fallback = MAPPER.createObjectNode();
				fallback.put("input", fn.arguments());
				tuNode.set("input", fallback);
			}
		} else {
			tuNode.set("input", MAPPER.createObjectNode());
		}
	}
}
