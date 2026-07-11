package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

final class ToolResultEntryBuilder {

	private static final JsonMapper MAPPER = new JsonMapper();

	private ToolResultEntryBuilder() {}

	static ObjectNode build(List<Message> toolResults, String modelId) {
		ObjectNode entry = MAPPER.createObjectNode();
		ObjectNode userMsg = entry.putObject("userInputMessage");
		userMsg.put("content", ".");
		userMsg.put("modelId", modelId);
		userMsg.put("origin", "AI_EDITOR");
		ObjectNode ctx = userMsg.putObject("userInputMessageContext");
		ArrayNode resultsNode = ctx.putArray("toolResults");
		for (Message tr : toolResults) {
			ToolResultAppender.appendResult(resultsNode, tr);
		}
		return entry;
	}
}
