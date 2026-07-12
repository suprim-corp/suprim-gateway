package dev.suprim.gateway.provider.antigravity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AntigravityStreamConverter {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	static String extractText(String geminiData) {
		try {
			JsonNode root = MAPPER.readTree(geminiData);
			JsonNode responseNode = root.get("response");
			if (responseNode == null) responseNode = root;

			JsonNode candidates = responseNode.get("candidates");
			if (candidates == null || candidates.isEmpty()) return null;

			JsonNode candidate = candidates.get(0);
			JsonNode content = candidate.get("content");
			if (content == null || !content.has("parts")) return null;

			JsonNode parts = content.get("parts");
			if (parts.isEmpty() || !parts.get(0).has("text")) return null;

			String text = parts.get(0).get("text").asString();

			if (candidate.has("finishReason") && !candidate.get("finishReason").isNull()) {
				if (text.isEmpty()) return null;
			}

			return text;
		} catch (Exception e) {
			return null;
		}
	}

	static String buildChunkPublic(String id, String model, String text) {
		return buildChunk(id, model, text, null);
	}

	static String buildStopChunk(String model, String id) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("id", id);
		root.put("object", "chat.completion.chunk");
		root.put("model", model);
		var choices = root.putArray("choices");
		ObjectNode choice = choices.addObject();
		choice.put("index", 0);
		choice.putObject("delta");
		choice.put("finish_reason", "stop");

		try {
			return "data: " + MAPPER.writeValueAsString(root) + "\n\n";
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static String buildDoneEvent() {
		return "data: [DONE]\n\n";
	}

	private static String buildChunk(String id, String model, String text, String finishReason) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("id", id);
		root.put("object", "chat.completion.chunk");
		root.put("model", model);
		var choices = root.putArray("choices");
		ObjectNode choice = choices.addObject();
		choice.put("index", 0);

		ObjectNode delta = choice.putObject("delta");
		if (text != null && !text.isEmpty()) {
			delta.put("content", text);
		}

		if (finishReason != null) {
			choice.put("finish_reason", finishReason);
		} else {
			choice.putNull("finish_reason");
		}

		try {
			return "data: " + MAPPER.writeValueAsString(root) + "\n\n";
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
