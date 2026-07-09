package dev.suprim.gateway.proxy;

import java.util.Set;
import java.util.function.Consumer;

public class StreamingContentFilter {

	private static final Set<String> FILTERED_TAGS = Set.of(
			"thinking",
			"tool_use",
			"tool_result"
	);

	private final StringBuilder buffer = new StringBuilder();
	private int depth = 0;
	private String activeTag = null;

	public void accept(String chunk, Consumer<String> emit) {
		for (int i = 0; i < chunk.length(); i++) {
			char c = chunk.charAt(i);

			if (activeTag != null) {
				buffer.append(c);
				trackDepth(c);
				if (depth == 0) {
					buffer.setLength(0);
					activeTag = null;
				}
				continue;
			}

			if (c == '<') {
				buffer.append(c);
				continue;
			}

			if (!buffer.isEmpty()) {
				buffer.append(c);
				if (c == '>') {
					String tag = buffer.toString();
					String tagName = parseTagName(tag);
					boolean isClosing = tag.charAt(1) == '/';

					if (FILTERED_TAGS.contains(tagName)) {
						if (isClosing) {
							// Orphaned closing tag — drop
							buffer.setLength(0);
						} else {
							activeTag = tagName;
							depth = 1;
						}
					} else {
						emit.accept(tag);
						buffer.setLength(0);
					}
				}
				continue;
			}

			emit.accept(String.valueOf(c));
		}
	}

	public void flush(Consumer<String> emit) {
		if (!buffer.isEmpty() && activeTag == null) {
			emit.accept(buffer.toString());
		}
		buffer.setLength(0);
		activeTag = null;
		depth = 0;
	}

	private void trackDepth(char c) {
		if (c != '>') return;
		String buf = buffer.toString();
		String closingTag = "</" + activeTag + ">";
		String openingTag = "<" + activeTag + ">";
		// Check for nested opens/closes from the current buffer tail
		if (buf.endsWith(closingTag)) {
			depth--;
		} else if (buf.endsWith(openingTag)) {
			depth++;
		}
	}

	private static String parseTagName(String tag) {
		int start = tag.startsWith("</") ? 2 : 1;
		int end = tag.length() - 1; // before '>'
		if (end <= start) return "";
		String name = tag.substring(start, end).trim();
		int space = name.indexOf(' ');
		if (space > 0) name = name.substring(0, space);
		return name.toLowerCase();
	}
}
