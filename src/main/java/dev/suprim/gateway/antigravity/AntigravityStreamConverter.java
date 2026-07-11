package dev.suprim.gateway.antigravity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AntigravityStreamConverter {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	static String convertChunk(String geminiData, String model, String id) {
		try {
			JsonNode root = MAPPER.readTree(geminiData);
			JsonNode candidates = root.get("candidates");
			if (candidates == null || candidates.isEmpty()) return null;

			JsonNode candidate = candidates.get(0);
			JsonNode content = candidate.get("content");
			String text = "";
			if (content != null && content.has("parts")) {
				JsonNode parts = content.get("parts");
				if (!parts.isEmpty() && parts.get(0).has("text")) {
					text = parts.get(0).get("text").asString();
				}
			}

			String finishReason = null;
			if (candidate.has("finishReason") && !candidate.get("finishReason").isNull()) {
				finishReason = mapFinishReason(candidate.get("finishReason").asString());
			}

			return buildChunk(id, model, text, finishReason);
		} catch (Exception e) {
			return null;
		}
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

	private static String mapFinishReason(String geminiReason) {
		return switch (geminiReason) {
			case "STOP" -> "stop";
			case "MAX_TOKENS" -> "length";
			case "SAFETY" -> "content_filter";
			default -> geminiReason.toLowerCase();
		};
	}
}
