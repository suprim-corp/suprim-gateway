package dev.suprim.gateway.proxy.token;

import java.util.regex.Pattern;

final class ToolResultCompressor {

	private static final int MINIMUM_LENGTH = 500;
	private static final int MAXIMUM_LENGTH = 10 * 1024 * 1024;
	private static final int SMART_TRUNCATE_LENGTH = 8_000;
	private static final Pattern LOG_PREFIX = Pattern.compile(
			"^(?:\\d{4}-\\d{2}-\\d{2}[T ][^ ]+|\\[[^]]+])\\s+(.*)$"
	);

	private ToolResultCompressor() {}

	static String compress(String source) {
		if (source == null || source.length() < MINIMUM_LENGTH || source.length() > MAXIMUM_LENGTH) {
			return source;
		}
		try {
			String candidate = deduplicateConsecutiveLogs(source);
			if (candidate.length() >= source.length()) {
				candidate = smartTruncate(source);
			}
			return isUsable(source, candidate) ? candidate : source;
		} catch (RuntimeException ignored) {
			return source;
		}
	}

	private static String deduplicateConsecutiveLogs(String source) {
		String[] lines = source.split("\\R", -1);
		StringBuilder result = new StringBuilder(source.length());
		String previousMessage = null;
		int repeats = 0;
		for (String line : lines) {
			java.util.regex.Matcher matcher = LOG_PREFIX.matcher(line);
			String message = matcher.matches() ? matcher.group(1) : null;
			if (message != null && message.equals(previousMessage)) {
				repeats++;
				continue;
			}
			if (repeats > 0) {
				appendLine(result, "...[previous log repeated " + repeats + " times]...");
			}
			appendLine(result, line);
			previousMessage = message;
			repeats = 0;
		}
		if (repeats > 0) {
			appendLine(result, "...[previous log repeated " + repeats + " times]...");
		}
		return result.toString();
	}

	private static void appendLine(StringBuilder result, String line) {
		if (!result.isEmpty()) {
			result.append('\n');
		}
		result.append(line);
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
