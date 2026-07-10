package dev.suprim.gateway.proxy;

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

	private void handleMetering(JsonNode obj, List<KiroEvent> events) {
		String eventType = obj.has("__eventType") ? obj.get("__eventType")
		                                               .asString() : null;
		if ("meteringEvent".equals(eventType)) {
			if (obj.has("usage")) {
				double usage = obj.get("usage").asDouble();
				if (usage > 0) {
					events.add(KiroEvent.metering(usage));
				}
			}
			return;
		}
		if (obj.has("meteringEvent")) {
			JsonNode metering = obj.get("meteringEvent");
			if (metering.has("usage")) {
				double usage = metering.get("usage").asDouble();
				if (usage > 0) {
					events.add(KiroEvent.metering(usage));
				}
			}
		}
	}

	private void addContent(JsonNode contentNode, List<KiroEvent> events) {
		String content = extractContent(contentNode);
		if (content != null && !content.isEmpty()) {
			events.add(KiroEvent.content(content));
		}
	}

	private String extractContent(JsonNode node) {
		if (node == null) return null;
		if (node.isString()) return node.asString();
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
