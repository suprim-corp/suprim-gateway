package dev.suprim.gateway.proxy;

import java.util.function.Consumer;

public class StreamingContentFilter {

	private final StringBuilder tagBuffer = new StringBuilder();
	private boolean insideFilteredTag = false;
	private String currentTagName = null;

	private static final String[] FILTERED_TAGS = {"thinking", "tool_use", "tool_result"};

	public void accept(String chunk, Consumer<String> emit) {
		if (insideFilteredTag) {
			tagBuffer.append(chunk);
			checkClosingTag(emit);
			return;
		}

		int tagStart = chunk.indexOf('<');
		if (tagStart == -1) {
			emit.accept(chunk);
			return;
		}

		if (tagStart > 0) {
			emit.accept(chunk.substring(0, tagStart));
		}
		tagBuffer.append(chunk.substring(tagStart));
		processTagBuffer(emit);
	}

	public void flush(Consumer<String> emit) {
		if (tagBuffer.isEmpty()) return;
		if (!insideFilteredTag) {
			emit.accept(tagBuffer.toString());
		}
		tagBuffer.setLength(0);
		insideFilteredTag = false;
		currentTagName = null;
	}

	private void processTagBuffer(Consumer<String> emit) {
		String buf = tagBuffer.toString();

		int closeAngle = buf.indexOf('>');
		if (closeAngle == -1) return; // incomplete tag, wait for more data

		String tag = buf.substring(0, closeAngle + 1);
		String tagName = extractOpenTagName(tag);

		if (isFiltered(tagName)) {
			insideFilteredTag = true;
			currentTagName = tagName;
			checkClosingTag(emit);
		} else {
			// Not filtered — emit everything buffered
			emit.accept(buf);
			tagBuffer.setLength(0);
		}
	}

	private void checkClosingTag(Consumer<String> emit) {
		String closingTag = "</" + currentTagName + ">";
		int closeIdx = tagBuffer.indexOf(closingTag);
		if (closeIdx == -1) return;

		// Tag closed — drop entire content, process remainder
		int afterClose = closeIdx + closingTag.length();
		String remainder = tagBuffer.substring(afterClose);
		tagBuffer.setLength(0);
		insideFilteredTag = false;
		currentTagName = null;

		if (!remainder.isEmpty()) {
			accept(remainder, emit);
		}
	}

	private static String extractOpenTagName(String tag) {
		// "<thinking>" → "thinking", "</thinking>" → "thinking"
		int start = tag.startsWith("</") ? 2 : 1;
		int end = tag.indexOf('>');
		if (end <= start) return "";
		String name = tag.substring(start, end).trim();
		int space = name.indexOf(' ');
		if (space > 0) name = name.substring(0, space);
		return name.toLowerCase();
	}

	private static boolean isFiltered(String tagName) {
		for (String filtered : FILTERED_TAGS) {
			if (filtered.equals(tagName)) return true;
		}
		return false;
	}
}
