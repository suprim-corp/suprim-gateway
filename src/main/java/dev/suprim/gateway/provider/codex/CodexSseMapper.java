package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Maps Codex/OpenAI Responses API SSE data events → {@link KiroEvent}.
 */
final class CodexSseMapper {

	private CodexSseMapper() {}

	static Optional<KiroEvent> toEvent(JsonNode node) {
		if (node == null || !node.has("type")) {
			return Optional.empty();
		}
		String type = node.get("type").asString();
		return switch (type) {
			case "response.output_text.delta" -> textDelta(node);
			case "response.reasoning_summary_text.delta" ->
					reasoningDelta(node);
			case "response.output_item.done" -> outputItemDone(node);
			default -> Optional.empty();
		};
	}

	static Optional<Integer> usageOutputTokens(JsonNode node) {
		if (node == null || !"response.completed".equals(nodePathType(node))) {
			return Optional.empty();
		}
		return Optional.ofNullable(node.get("response"))
		               .map(r -> r.get("usage"))
		               .map(u -> u.get("output_tokens"))
		               .filter(JsonNode::isNumber)
		               .map(JsonNode::asInt);
	}

	static Optional<Integer> usageInputTokens(JsonNode node) {
		if (node == null || !"response.completed".equals(nodePathType(node))) {
			return Optional.empty();
		}
		return Optional.ofNullable(node.get("response"))
		               .map(r -> r.get("usage"))
		               .map(u -> u.get("input_tokens"))
		               .filter(JsonNode::isNumber)
		               .map(JsonNode::asInt);
	}

	private static String nodePathType(JsonNode node) {
		return node.has("type") ? node.get("type").asString() : "";
	}

	private static Optional<KiroEvent> textDelta(JsonNode node) {
		return Optional.ofNullable(node.get("delta"))
		               .map(JsonNode::asString)
		               .filter(s -> !s.isEmpty())
		               .map(KiroEvent::content);
	}

	private static Optional<KiroEvent> reasoningDelta(JsonNode node) {
		return Optional.ofNullable(node.get("delta"))
		               .map(JsonNode::asString)
		               .filter(s -> !s.isEmpty())
		               .map(KiroEvent::reasoning);
	}

	private static Optional<KiroEvent> outputItemDone(JsonNode node) {
		JsonNode item = node.get("item");
		if (item == null || !item.has("type")) {
			return Optional.empty();
		}
		if (!"function_call".equals(item.get("type").asString())) {
			return Optional.empty();
		}
		String name = Optional.ofNullable(item.get("name"))
		                      .map(JsonNode::asString)
		                      .orElse("unknown");
		String args = Optional.ofNullable(item.get("arguments"))
		                      .map(JsonNode::asString)
		                      .filter(a -> !a.isEmpty())
		                      .orElse("{}");
		String callId = Optional.ofNullable(item.get("call_id"))
		                        .map(JsonNode::asString)
		                        .or(() -> Optional.ofNullable(item.get("id"))
		                                          .map(JsonNode::asString)
		                        )
		                        .orElse(null);
		return Optional.of(KiroEvent.toolUse(name, args, callId));
	}
}
