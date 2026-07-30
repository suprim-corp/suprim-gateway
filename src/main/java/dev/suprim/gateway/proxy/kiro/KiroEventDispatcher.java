package dev.suprim.gateway.proxy.kiro;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class KiroEventDispatcher {

	private String currentToolName;
	private String currentToolId;
	private final StringBuilder toolArgs = new StringBuilder();

	public List<KiroEvent> dispatch(JsonNode obj) {
		List<KiroEvent> events = new ArrayList<>();

		if (obj.has("assistantResponseEvent")) {
			JsonNode node = obj.get("assistantResponseEvent");
			handleContent(node, events);
			handleReasoning(node, events);
			handleToolUse(node, events);
			return events;
		}

		handleReasoning(obj, events);
		handleContent(obj, events);
		handleToolUse(obj, events);
		handleSupplementary(obj, events);
		handleBareToolEvent(obj, events);
		handleUsage(obj, events);
		handleMetering(obj, events);

		return events;
	}

	private void handleContent(JsonNode node, List<KiroEvent> events) {
		if (node.has("assistantResponseEvent")) {
			addContent(
					node.get("assistantResponseEvent").get("content"),
					events
			);
		}
		addContent(node.get("content"), events);
	}

	private void handleReasoning(JsonNode node, List<KiroEvent> events) {
		JsonNode reasoning = node.get("reasoningContentEvent");
		if (reasoning == null) return;
		String text = reasoning.has("text") ? reasoning.get("text")
		                                               .asString() : null;
		if (text != null && !text.isEmpty()) {
			events.add(KiroEvent.reasoning(text));
		}
	}

	private void handleToolUse(JsonNode node, List<KiroEvent> events) {
		if (node.has("toolUseEvent")) {
			processToolEvent(node.get("toolUseEvent"), events);
		}
	}

	private void handleSupplementary(JsonNode obj, List<KiroEvent> events) {
		if (!obj.has("supplementaryWebChatEvent")) return;
		addContent(obj.get("supplementaryWebChatEvent").get("content"), events);
	}

	private void handleBareToolEvent(JsonNode obj, List<KiroEvent> events) {
		if (obj.has("name") || obj.has("toolUseId") || obj.has("stop")) {
			processToolEvent(obj, events);
		}
		if (obj.has("toolUseEvent") && !obj.has("assistantResponseEvent")) {
			processToolEvent(obj.get("toolUseEvent"), events);
		}
	}

	private void processToolEvent(JsonNode toolNode, List<KiroEvent> events) {
		if (toolNode == null) return;

		String name = toolNode.has("name") ? toolNode.get("name")
		                                             .asString() : null;
		String input = null;
		if (toolNode.has("input")) {
			JsonNode inputNode = toolNode.get("input");
			if (inputNode.isString()) {
				input = inputNode.asString();
			} else if (inputNode.isObject()) {
				toolArgs.setLength(0);
				input = inputNode.toString();
			}
		}
		boolean stop = toolNode.has("stop") && toolNode.get("stop").asBoolean();
		String toolUseId = toolNode.has("toolUseId") ? toolNode.get("toolUseId")
		                                                       .asString() : null;

		if (name != null && currentToolName == null) {
			currentToolName = name;
			currentToolId =
					toolUseId != null ? toolUseId : "tool_" + System.nanoTime();
			toolArgs.setLength(0);
		} else if (name != null && !name.equals(currentToolName)) {
			// New tool starting — finish previous if any
			events.add(
					KiroEvent.toolUse(
							currentToolName,
							toolArgs.toString(),
							currentToolId
					)
			);
			currentToolName = name;
			currentToolId =
					toolUseId != null ? toolUseId : "tool_" + System.nanoTime();
			toolArgs.setLength(0);
		}
		if (input != null) {
			toolArgs.append(input);
		}
		if (stop && currentToolName != null) {
			events.add(
					KiroEvent.toolUse(
							currentToolName,
							toolArgs.toString(),
							currentToolId
					)
			);
			currentToolName = null;
			currentToolId = null;
			toolArgs.setLength(0);
		}
	}

	private void handleUsage(JsonNode obj, List<KiroEvent> events) {
		String eventType = text(obj.get("__eventType"));
		JsonNode metrics = "metricsEvent".equals(eventType)
				? obj
				: obj.get("metricsEvent");
		if (metrics != null && metrics.isObject()) {
			Integer promptTokens = nonNegativeInteger(metrics, "inputTokens");
			Integer outputTokens = nonNegativeInteger(metrics, "outputTokens");
			Integer cacheReadTokens = nonNegativeInteger(
					metrics,
					"cacheReadInputTokens",
					"cache_read_input_tokens"
			);
			Integer cacheCreationTokens = nonNegativeInteger(
					metrics,
					"cacheCreationInputTokens",
					"cache_creation_input_tokens"
			);
			if (promptTokens != null || outputTokens != null ||
			    cacheReadTokens != null || cacheCreationTokens != null) {
				events.add(
						KiroEvent.usage(
								promptTokens,
								outputTokens,
								cacheReadTokens,
								cacheCreationTokens,
								null
						)
				);
			}
		}

		JsonNode context = "contextUsageEvent".equals(eventType)
				? obj
				: obj.get("contextUsageEvent");
		Double percentage = nonNegativeDouble(
				context,
				"contextUsagePercentage"
		);
		if (percentage != null) {
			events.add(KiroEvent.usage(null, null, null, null, percentage));
		}
	}

	private void handleMetering(JsonNode obj, List<KiroEvent> events) {
		String eventType = text(obj.get("__eventType"));
		JsonNode metering = "meteringEvent".equals(eventType)
				? obj
				: obj.get("meteringEvent");
		Double usage = nonNegativeDouble(metering, "usage");
		if (usage != null) {
			events.add(KiroEvent.metering(usage));
		}
	}

	private static Integer nonNegativeInteger(JsonNode node, String... fields) {
		if (node == null || !node.isObject()) {
			return null;
		}
		for (String field : fields) {
			JsonNode value = node.get(field);
			if (value != null && value.isIntegralNumber() &&
			    value.canConvertToInt() &&
			    value.asInt() >= 0) {
				return value.asInt();
			}
		}
		return null;
	}

	private static Double nonNegativeDouble(JsonNode node, String field) {
		if (node == null || !node.isObject()) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || !value.isNumber()) {
			return null;
		}
		double number = value.asDouble();
		return Double.isFinite(number) && number >= 0 ? number : null;
	}

	private static String text(JsonNode node) {
		return node != null && node.isString() ? node.asString() : null;
	}

	private void addContent(JsonNode contentNode, List<KiroEvent> events) {
		String content = extractContent(contentNode);
		if (content != null && !content.isEmpty()) {
			events.add(KiroEvent.content(content));
		}
	}

	private String extractContent(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isString()) {
			return node.asString();
		}
		if (node.isArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode item : node) {
				if (item.has("text")) sb.append(item.get("text").asString());
			}
			return sb.toString();
		}
		return null;
	}
}
