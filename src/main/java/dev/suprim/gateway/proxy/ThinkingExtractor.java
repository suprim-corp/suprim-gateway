package dev.suprim.gateway.proxy;

public final class ThinkingExtractor {

	private ThinkingExtractor() {}

	public static String strip(String content) {
		if (content == null) return "";
		String result = content;
		while (true) {
			int start = result.indexOf("<thinking>");
			if (start == -1) break;
			int end = result.indexOf("</thinking>", start);
			if (end == -1) break;
			result = result.substring(0, start) + result.substring(end + 11);
		}
		return result.trim();
	}
}
