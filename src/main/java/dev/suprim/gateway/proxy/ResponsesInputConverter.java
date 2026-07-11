package dev.suprim.gateway.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ResponsesInputConverter {

	private ResponsesInputConverter() {}

	public static List<Message> convert(Object input) {
		if (input instanceof String s) {
			return List.of(MessageHandler.toMessage("user", s));
		}
		if (!(input instanceof List<?> items)) {
			return List.of();
		}

		List<Message> messages = new ArrayList<>();
		List<Message.ToolCall> pendingToolCalls = new ArrayList<>();
		String pendingAssistant = "";

		for (Object item : items) {
			if (!(item instanceof Map<?, ?> m)) continue;
			Object typeObj = m.get("type");
			String type = typeObj != null ? typeObj.toString() : "message";

			switch (type) {
				case "function_call" ->
						pendingToolCalls.add(FunctionCallHandler.toToolCall(m));

				case "function_call_output" -> {
					if (!pendingToolCalls.isEmpty()) {
						messages.add(FunctionCallHandler.toAssistantWithTools(
								pendingAssistant,
								new ArrayList<>(pendingToolCalls)
						));
						pendingToolCalls.clear();
						pendingAssistant = "";
					}
					messages.add(FunctionCallHandler.toToolResult(m));
				}

				default -> {
					if (!pendingToolCalls.isEmpty()) {
						messages.add(FunctionCallHandler.toAssistantWithTools(
								pendingAssistant,
								new ArrayList<>(pendingToolCalls)
						));
						pendingToolCalls.clear();
						pendingAssistant = "";
					}
					String role = MessageHandler.resolveRole(m);
					String text = MessageHandler.resolveContent(m);
					if ("assistant".equals(role)) {
						if (!pendingAssistant.isEmpty()) messages.add(
								MessageHandler.toMessage("assistant", pendingAssistant));
						pendingAssistant = text;
					} else {
						if (!pendingAssistant.isEmpty()) {
							messages.add(MessageHandler.toMessage(
									"assistant", pendingAssistant));
							pendingAssistant = "";
						}
						messages.add(MessageHandler.toMessage(role, text));
					}
				}
			}
		}

		if (!pendingToolCalls.isEmpty()) {
			messages.add(FunctionCallHandler.toAssistantWithTools(
					pendingAssistant,
					new ArrayList<>(pendingToolCalls)
			));
		} else if (!pendingAssistant.isEmpty()) {
			messages.add(MessageHandler.toMessage("assistant", pendingAssistant));
		}

		return messages;
	}
}
