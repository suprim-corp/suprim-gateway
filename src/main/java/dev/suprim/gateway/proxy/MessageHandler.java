package dev.suprim.gateway.proxy;

import java.util.Map;

final class MessageHandler {

	private MessageHandler() {}

	static String resolveRole(Map<?, ?> m) {
		Object roleObj = m.get("role");
		String role = roleObj != null ? roleObj.toString() : "user";
		if ("developer".equals(role)) role = "system";
		return role;
	}

	static String resolveContent(Map<?, ?> m) {
		return ContentExtractor.fromResponsesBlock(m.get("content"));
	}

	static Message toMessage(String role, String content) {
		return Message.of(role, content);
	}
}
