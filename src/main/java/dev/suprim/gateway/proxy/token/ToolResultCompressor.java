package dev.suprim.gateway.proxy.token;

import java.util.LinkedHashSet;
import java.util.Set;

final class ToolResultCompressor {

	private static final int MINIMUM_LENGTH = 500;
	private static final int MAXIMUM_LENGTH = 10 * 1024 * 1024;
	private static final int SMART_TRUNCATE_LENGTH = 8_000;

	private ToolResultCompressor() {}

	static String compress(String source) {
		if (source == null || source.length() < MINIMUM_LENGTH || source.length() > MAXIMUM_LENGTH) {
			return source;
		}
		try {
			String candidate = deduplicateLines(source);
			if (candidate.length() >= source.length()) {
				candidate = smartTruncate(source);
			}
			return isUsable(source, candidate) ? candidate : source;
		} catch (RuntimeException ignored) {
			return source;
		}
	}

	private static String deduplicateLines(String source) {
		String[] lines = source.split("\\R", -1);
		Set<String> seen = new LinkedHashSet<>();
		StringBuilder result = new StringBuilder();
		for (String line : lines) {
			if (seen.add(line)) {
				if (!result.isEmpty()) {
					result.append('\n');
				}
				result.append(line);
			}
		}
		return result.toString();
	}

	private static String smartTruncate(String source) {
		if (source.length() <= SMART_TRUNCATE_LENGTH) {
			return source;
		}
		int half = SMART_TRUNCATE_LENGTH / 2;
		return source.substring(0, half) + "\n...[output truncated]...\n" +
		       source.substring(source.length() - half);
	}

	private static boolean isUsable(String source, String candidate) {
		return candidate != null && !candidate.isBlank() && candidate.length() < source.length();
	}
}
