package dev.suprim.gateway.proxy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FunctionCallHandler {

	private FunctionCallHandler() {}

	static Map<String, Object> toToolCall(Map<?, ?> m) {
		String callId = stringOrEmpty(m.get("call_id"));
		String name = stringOrEmpty(m.get("name"));
		String arguments = m.get("arguments") != null ? m.get("arguments")
		                                                 .toString() : "{}";
		HashMap<String, Object> tc = new HashMap<>();
		tc.put("id", callId);
		tc.put("type", "function");
		tc.put("function", Map.of("name", name, "arguments", arguments));
		return tc;
	}

	static Map<String, Object> toToolResult(Map<?, ?> m) {
		HashMap<String, Object> toolMsg = new HashMap<>();
		toolMsg.put("role", "tool");
		toolMsg.put(
				"content",
				m.get("output") != null ? m.get("output").toString() : ""
		);
		toolMsg.put(
				"tool_call_id",
				m.get("call_id") != null ? m.get("call_id").toString() : ""
		);
		return toolMsg;
	}

	static Map<String, Object> toAssistantWithTools(
			String content,
			List<Map<String, Object>> toolCalls
	) {
		HashMap<String, Object> msg = new HashMap<>();
		msg.put("role", "assistant");
		msg.put("content", content);
		msg.put("tool_calls", toolCalls);
		return msg;
	}

	private static String stringOrEmpty(Object obj) {
		return obj != null ? obj.toString() : "";
	}
}
