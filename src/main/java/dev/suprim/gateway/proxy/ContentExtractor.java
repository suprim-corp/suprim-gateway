package dev.suprim.gateway.proxy;

import java.util.List;
import java.util.Map;

final class ContentExtractor {

	private ContentExtractor() {}

	static String fromMessage(Map<String, Object> msg) {
		Object content = msg.get("content");
		if (content instanceof String s) return s;
		if (content instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			for (Object item : list) {
				if (item instanceof Map<?, ?> m) {
					if ("text".equals(m.get("type"))) sb.append(m.get("text"));
				}
			}
			return sb.toString();
		}
		return "";
	}

	static String fromResponsesBlock(Object content) {
		if (content instanceof String s) return s;
		if (content instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			for (Object block : list) {
				if (block instanceof Map<?, ?> m) {
					Object typeObj = m.get("type");
					String bType = typeObj != null ? typeObj.toString() : "";
					if ("input_text".equals(bType) || "text".equals(bType)) {
						Object text = m.get("text");
						if (text != null) sb.append(text);
					}
				}
			}
			return sb.toString();
		}
		return content != null ? content.toString() : "";
	}
}
