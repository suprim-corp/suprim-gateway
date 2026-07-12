package dev.suprim.gateway.provider.antigravity;

import lombok.Builder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AntigravityStreamConverter {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final ParsedChunk FINISHED =
			ParsedChunk.builder()
			           .finished(true)
			           .build();

	@Builder
	record ParsedChunk(
			String text, FunctionCall functionCall, boolean finished
	) {}

	@Builder
	record FunctionCall(String name, String args, String id) {}

	static ParsedChunk parseChunk(String geminiData) {
		try {
			JsonNode root = MAPPER.readTree(geminiData);
			JsonNode responseNode;
			if (root.get("response") == null) {
				responseNode = root;
			} else {
				responseNode = root.get("response");
			}

			JsonNode candidates = responseNode.get("candidates");
			if (candidates == null || candidates.isEmpty()) {
				return null;
			}

			JsonNode candidate = candidates.get(0);
			boolean finished = candidate.has("finishReason")
			                   && !candidate.get("finishReason").isNull();

			JsonNode content = candidate.get("content");
			if (content == null || !content.has("parts")) {
				return finished ? FINISHED : null;
			}

			JsonNode parts = content.get("parts");
			if (parts.isEmpty()) {
				return finished ? FINISHED : null;
			}

			JsonNode firstPart = parts.get(0);

			if (firstPart.has("functionCall")) {
				JsonNode fc = firstPart.get("functionCall");
				String name = fc.has("name") ? fc.get("name").asString() : "";
				String args = fc.has("args") ? fc.get("args").toString() : "{}";
				String id = fc.has("id") ? fc.get("id").asString() : null;
				return ParsedChunk.builder()
				                  .functionCall(
						                  FunctionCall.builder()
						                              .name(name)
						                              .args(args)
						                              .id(id)
						                              .build()
				                  )
				                  .finished(finished)
				                  .build();
			}

			if (firstPart.has("text")) {
				String text = firstPart.get("text").asString();
				if (text.isEmpty() && finished) return FINISHED;
				return ParsedChunk.builder()
				                  .text(text)
				                  .finished(finished)
				                  .build();
			}

			return finished ? FINISHED : null;
		} catch (Exception e) {
			return null;
		}
	}

	static String extractText(String geminiData) {
		ParsedChunk chunk = parseChunk(geminiData);
		if (chunk == null) return null;
		return chunk.text();
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

	private static String buildChunk(
			String id,
			String model,
			String text,
			String finishReason
	) {
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
