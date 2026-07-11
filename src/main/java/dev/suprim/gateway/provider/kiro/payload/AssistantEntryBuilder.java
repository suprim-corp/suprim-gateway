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

	static ObjectNode build(Message msg) {
		ObjectNode entry = MAPPER.createObjectNode();
		ObjectNode assistantMsg = entry.putObject("assistantResponseMessage");
		String content = ContentExtractor.fromMessage(msg);
		assistantMsg.put("content", content != null ? content : "");

		List<Message.ToolCall> toolCalls = msg.toolCalls();
		if (toolCalls != null && !toolCalls.isEmpty()) {
			ArrayNode toolUsesNode = assistantMsg.putArray("toolUses");
			for (Message.ToolCall tc : toolCalls) {
				appendToolUse(toolUsesNode, tc);
			}
		}
		return entry;
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
