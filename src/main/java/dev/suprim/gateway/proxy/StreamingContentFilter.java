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
	private final StringBuilder textBatch = new StringBuilder();
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
				flushTextBatch(emit);
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
							buffer.setLength(0);
						} else {
							activeTag = tagName;
							depth = 1;
						}
					} else {
						textBatch.append(tag);
						buffer.setLength(0);
					}
				}
				continue;
			}

			textBatch.append(c);
		}
		flushTextBatch(emit);
	}

	public void flush(Consumer<String> emit) {
		if (!buffer.isEmpty() && activeTag == null) {
			textBatch.append(buffer);
			buffer.setLength(0);
		}
		flushTextBatch(emit);
		activeTag = null;
		depth = 0;
	}

	private void flushTextBatch(Consumer<String> emit) {
		if (textBatch.isEmpty()) return;
		emit.accept(textBatch.toString());
		textBatch.setLength(0);
	}

	private void trackDepth(char c) {
		if (c != '>') return;
		String buf = buffer.toString();
		String closingTag = "</" + activeTag + ">";
		String openingTag = "<" + activeTag + ">";
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
