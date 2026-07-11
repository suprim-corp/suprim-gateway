package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

final class UserEntryBuilder {

	private static final JsonMapper MAPPER = new JsonMapper();

	private UserEntryBuilder() {}

	static ObjectNode build(Message msg, String modelId) {
		String content = ContentExtractor.fromMessage(msg);
		List<ContentExtractor.KiroImage> images =
				ContentExtractor.extractImages(msg);

		ObjectNode entry = MAPPER.createObjectNode();
		ObjectNode userMsg = entry.putObject("userInputMessage");
		userMsg.put(
				"content",
				content != null && !content.isEmpty() ? content : "."
		);
		userMsg.put("modelId", modelId);
		userMsg.put("origin", "AI_EDITOR");
		ImageAppender.append(userMsg, images);
		return entry;
	}
}
