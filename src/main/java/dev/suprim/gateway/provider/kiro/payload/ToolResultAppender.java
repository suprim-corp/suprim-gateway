package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

final class ToolResultAppender {

	private static final int MAX_CONTENT_LEN = 4000;

	private ToolResultAppender() {}

	static void appendResult(ArrayNode resultsNode, Message toolResult) {
		ObjectNode resultObj = resultsNode.addObject();
		resultObj.put(
				"toolUseId",
				Optional.ofNullable(toolResult.toolCallId()).orElse("")
		);
		ArrayNode contentArr = resultObj.putArray("content");
		ObjectNode textObj = contentArr.addObject();
		textObj.put("text", truncate(ContentExtractor.fromMessage(toolResult)));
		resultObj.put("status", "success");
	}

	static String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() <= MAX_CONTENT_LEN ? s : s.substring(
				0,
				MAX_CONTENT_LEN
		);
	}
}
