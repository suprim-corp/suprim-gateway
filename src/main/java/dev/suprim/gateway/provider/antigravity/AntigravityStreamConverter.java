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
			String text, FunctionCall functionCall, boolean finished,
			String thoughtSignature, Usage usage, Double consumedCredits
	) {}

	@Builder
	record FunctionCall(String name, String args, String id) {}

	/**
	 * Token counts the upstream reports for the request so far, from
	 * {@code response.usageMetadata}. These are the counts the upstream billed, so they
	 * replace the gateway's own estimate whenever a chunk carries them.
	 * <p>
	 * The counts are cumulative for the whole request rather than per chunk, and only the
	 * later chunks carry them, so the last one seen is the total. {@code thoughtsTokens}
	 * covers reasoning the model did without emitting it; it is already part of
	 * {@code totalTokens} but not of {@code completionTokens}.
	 */
	@Builder
	record Usage(
			Integer promptTokens,
			Integer completionTokens,
			Integer totalTokens,
			Integer thoughtsTokens
	) {}

	static ParsedChunk parseChunk(String geminiData) {
		try {
			JsonNode root = MAPPER.readTree(geminiData);
			JsonNode responseNode;
			if (root.get("response") == null) {
				responseNode = root;
			} else {
				responseNode = root.get("response");
			}

			// Reported on the wrapper and on the inner response respectively, and only on
			// some chunks — a chunk that carries neither leaves both null.
			Double consumedCredits = parseCredits(root.get("consumedCredits"));
			Usage usage = parseUsage(responseNode.get("usageMetadata"));

			JsonNode candidates = responseNode.get("candidates");
			if (candidates == null || candidates.isEmpty()) {
				// The final chunk of a request often carries usage and nothing else.
				return usage == null && consumedCredits == null
						? null
						: ParsedChunk.builder()
						             .usage(usage)
						             .consumedCredits(consumedCredits)
						             .build();
			}

			JsonNode candidate = candidates.get(0);
			boolean finished = candidate.has("finishReason")
			                   && !candidate.get("finishReason").isNull();

			JsonNode content = candidate.get("content");
			if (content == null || !content.has("parts")) {
				return finishedChunk(finished, usage, consumedCredits);
			}

			JsonNode parts = content.get("parts");
			if (parts.isEmpty()) {
				return finishedChunk(finished, usage, consumedCredits);
			}

			// Extract thoughtSignature from any part that has it
			String thoughtSignature = null;
			for (int i = 0; i < parts.size(); i++) {
				JsonNode part = parts.get(i);
				if (part.has("thoughtSignature")) {
					thoughtSignature = part.get("thoughtSignature").asString();
					break;
				}
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
				                  .thoughtSignature(thoughtSignature)
				                  .usage(usage)
				                  .consumedCredits(consumedCredits)
				                  .build();
			}

			if (firstPart.has("text")) {
				String text = firstPart.get("text").asString();
				if (text.isEmpty() && finished) {
					return ParsedChunk.builder()
					                  .finished(true)
					                  .thoughtSignature(thoughtSignature)
					                  .usage(usage)
					                  .consumedCredits(consumedCredits)
					                  .build();
				}
				return ParsedChunk.builder()
				                  .text(text)
				                  .finished(finished)
				                  .thoughtSignature(thoughtSignature)
				                  .usage(usage)
				                  .consumedCredits(consumedCredits)
				                  .build();
			}

			return finishedChunk(finished, usage, consumedCredits);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * A chunk carrying no content of its own. Reuses the shared {@link #FINISHED} instance
	 * only when there is nothing else to report, so a chunk that ends the stream while also
	 * carrying usage still keeps it.
	 */
	private static ParsedChunk finishedChunk(
			boolean finished,
			Usage usage,
			Double consumedCredits
	) {
		if (usage == null && consumedCredits == null) {
			return finished ? FINISHED : null;
		}
		return ParsedChunk.builder()
		                  .finished(finished)
		                  .usage(usage)
		                  .consumedCredits(consumedCredits)
		                  .build();
	}

	/**
	 * Reads {@code response.usageMetadata}. Returns null when the node is absent or carries
	 * none of the counts, so callers can tell "not reported" from "reported as zero".
	 */
	private static Usage parseUsage(JsonNode usageMetadata) {
		if (usageMetadata == null || !usageMetadata.isObject()) {
			return null;
		}
		Integer prompt = intOrNull(usageMetadata, "promptTokenCount");
		Integer completion = intOrNull(usageMetadata, "candidatesTokenCount");
		Integer total = intOrNull(usageMetadata, "totalTokenCount");
		Integer thoughts = intOrNull(usageMetadata, "thoughtsTokenCount");
		if (prompt == null && completion == null && total == null && thoughts == null) {
			return null;
		}
		return Usage.builder()
		            .promptTokens(prompt)
		            .completionTokens(completion)
		            .totalTokens(total)
		            .thoughtsTokens(thoughts)
		            .build();
	}

	/**
	 * Reads a {@code Credits} node's {@code creditAmount}. The field is an
	 * {@code int64}, which JSON carries as a string, so both shapes are accepted.
	 */
	private static Double parseCredits(JsonNode credits) {
		if (credits == null || !credits.isObject()) {
			return null;
		}
		JsonNode amount = credits.get("creditAmount");
		if (amount == null || amount.isNull()) {
			return null;
		}
		try {
			return amount.isNumber()
					? amount.asDouble()
					: Double.parseDouble(amount.asString());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Integer intOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? value.asInt() : null;
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
