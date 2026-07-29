package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

final class ToolResultAppender {

	private ToolResultAppender() {}

	static void appendResult(ArrayNode resultsNode, Message toolResult) {
		ObjectNode resultObj = resultsNode.addObject();
		resultObj.put(
				"toolUseId",
				Optional.ofNullable(toolResult.toolCallId()).orElse("")
		);
		ArrayNode contentArr = resultObj.putArray("content");
		ObjectNode textObj = contentArr.addObject();
		textObj.put("text", ContentExtractor.fromMessage(toolResult));
		resultObj.put(
				"status",
				Boolean.TRUE.equals(toolResult.toolError()) ? "error" : "success"
		);
	}
}
