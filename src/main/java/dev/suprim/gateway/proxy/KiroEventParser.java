package dev.suprim.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class KiroEventParser {

	private static final Logger log = LoggerFactory.getLogger(KiroEventParser.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	public record KiroEvent(
			String type, String content, String toolName, String toolInput,
			String toolUseId, boolean toolStop
	) {}

	private final StringBuilder textBuffer = new StringBuilder();
	private byte[] binaryBuffer = new byte[0];
	private Boolean isBinaryMode = null;
	private String currentToolName;
	private String currentToolId;
	private final StringBuilder toolArgs = new StringBuilder();

	public List<KiroEvent> feed(byte[] chunk) {
		if (isBinaryMode == null) {
			isBinaryMode = chunk.length == 0 ||
			               (chunk[0] != '{' && chunk[0] != '[' &&
			                chunk[0] != '\n' &&
			                chunk[0] != '\r' && chunk[0] != ' ');
		}

		if (!isBinaryMode) {
			textBuffer.append(new String(chunk, StandardCharsets.UTF_8));
			return parseTextBuffer();
		}
		return feedBinary(chunk);
	}

	private List<KiroEvent> feedBinary(byte[] chunk) {
		byte[] newBuf = new byte[binaryBuffer.length + chunk.length];
		System.arraycopy(binaryBuffer, 0, newBuf, 0, binaryBuffer.length);
		System.arraycopy(chunk, 0, newBuf, binaryBuffer.length, chunk.length);
		binaryBuffer = newBuf;

		List<KiroEvent> events = new ArrayList<>();

		while (binaryBuffer.length >= 12) {
			ByteBuffer view = ByteBuffer.wrap(binaryBuffer);
			int totalLength = view.getInt(0);

			if (totalLength < 16 || totalLength > 16 * 1024 * 1024) {
				isBinaryMode = false;
				textBuffer.append(new String(
						binaryBuffer,
						StandardCharsets.UTF_8
				));
				binaryBuffer = new byte[0];
				events.addAll(parseTextBuffer());
				return events;
			}

			if (binaryBuffer.length < totalLength) break;

			int headersLength = view.getInt(4);
			int headersEnd = 12 + headersLength;
			int payloadEnd = totalLength - 4;

			if (payloadEnd > headersEnd) {
				String text = new String(
						binaryBuffer,
						headersEnd,
						payloadEnd - headersEnd,
						StandardCharsets.UTF_8
				).trim();
				if (text.startsWith("{")) {
					try {
						JsonNode obj = mapper.readTree(text);
						List<KiroEvent> parsed = processObject(obj);
						events.addAll(parsed);
					} catch (Exception e) {
						textBuffer.append(text);
						events.addAll(parseTextBuffer());
					}
				}
			}

			byte[] remaining = new byte[binaryBuffer.length - totalLength];
			System.arraycopy(
					binaryBuffer,
					totalLength,
					remaining,
					0,
					remaining.length
			);
			binaryBuffer = remaining;
		}

		return events;
	}

	private List<KiroEvent> parseTextBuffer() {
		List<KiroEvent> events = new ArrayList<>();
		int searchFrom = 0;

		while (true) {
			int start = indexOf(textBuffer, '{', searchFrom);
			if (start == -1) break;

			int end = findMatchingBrace(textBuffer, start);
			if (end == -1) break;

			String jsonStr = textBuffer.substring(start, end + 1);
			searchFrom = end + 1;

			try {
				JsonNode obj = mapper.readTree(jsonStr);
				events.addAll(processObject(obj));
			} catch (Exception e) {
				log.debug(
						"[Parser] malformed JSON: {}",
						jsonStr.length() > 200 ? jsonStr.substring(
								0,
								200
						) : jsonStr
				);
			}
		}

		if (searchFrom > 0) {
			textBuffer.delete(0, searchFrom);
		}
		return events;
	}

	private List<KiroEvent> processObject(JsonNode obj) {
		List<KiroEvent> events = new ArrayList<>();

		JsonNode node = obj;
		if (node.has("assistantResponseEvent")) {
			node = node.get("assistantResponseEvent");
			if (node.has("assistantResponseEvent")) {
				JsonNode inner = node.get("assistantResponseEvent");
				if (inner.has("content")) {
					String content = extractContent(inner.get("content"));
					if (content != null && !content.isEmpty()) {
						events.add(new KiroEvent(
								"content",
								content,
								null,
								null,
								null,
								false
						));
					}
				}
			}
			if (node.has("content")) {
				String content = extractContent(node.get("content"));
				if (content != null && !content.isEmpty()) {
					events.add(new KiroEvent(
							"content",
							content,
							null,
							null,
							null,
							false
					));
				}
			}
			if (node.has("toolUseEvent")) {
				events.addAll(processToolEvent(node.get("toolUseEvent")));
			}
		}

		if (obj.has("content") && !obj.has("assistantResponseEvent")) {
			String content = extractContent(obj.get("content"));
			if (content != null && !content.isEmpty()) {
				events.add(new KiroEvent(
						"content",
						content,
						null,
						null,
						null,
						false
				));
			}
		}

		if (obj.has("name") || obj.has("toolUseId") || obj.has("stop")) {
			events.addAll(processToolEvent(obj));
		}

		if (obj.has("toolUseEvent") && !obj.has("assistantResponseEvent")) {
			events.addAll(processToolEvent(obj.get("toolUseEvent")));
		}

		if (obj.has("supplementaryWebChatEvent")) {
			String content = extractContent(obj.get("supplementaryWebChatEvent")
			                                   .get("content"));
			if (content != null && !content.isEmpty()) {
				events.add(new KiroEvent(
						"content",
						content,
						null,
						null,
						null,
						false
				));
			}
		}

		return events;
	}

	private List<KiroEvent> processToolEvent(JsonNode toolNode) {
		List<KiroEvent> events = new ArrayList<>();
		if (toolNode == null) return events;

		String name = toolNode.has("name") ? toolNode.get("name")
		                                             .asText() : null;
		String input = toolNode.has("input") ? toolNode.get("input")
		                                               .asText() : null;
		boolean stop = toolNode.has("stop") && toolNode.get("stop").asBoolean();
		String toolUseId = toolNode.has("toolUseId") ? toolNode.get("toolUseId")
		                                                       .asText() : null;

		if (name != null) {
			currentToolName = name;
			currentToolId =
					toolUseId != null ? toolUseId : "tool_" + System.nanoTime();
			toolArgs.setLength(0);
		}

		if (input != null) {
			toolArgs.append(input);
		}

		if (stop && currentToolName != null) {
			events.add(new KiroEvent(
					"tool_use",
					null,
					currentToolName,
					toolArgs.toString(),
					currentToolId,
					true
			));
			currentToolName = null;
			currentToolId = null;
			toolArgs.setLength(0);
		}

		return events;
	}

	private String extractContent(JsonNode node) {
		if (node == null) return null;
		if (node.isTextual()) return node.asText();
		if (node.isArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode item : node) {
				if (item.has("text")) sb.append(item.get("text").asText());
			}
			return sb.toString();
		}
		return null;
	}

	private static int indexOf(StringBuilder sb, char c, int from) {
		for (int i = from; i < sb.length(); i++) {
			if (sb.charAt(i) == c) return i;
		}
		return -1;
	}

	private static int findMatchingBrace(StringBuilder sb, int start) {
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		for (int i = start; i < sb.length(); i++) {
			char c = sb.charAt(i);
			if (escape) {
				escape = false;
				continue;
			}
			if (c == '\\' && inString) {
				escape = true;
				continue;
			}
			if (c == '"') {
				inString = !inString;
				continue;
			}
			if (inString) continue;
			if (c == '{') depth++;
			else if (c == '}') {
				depth--;
				if (depth == 0) return i;
			}
		}
		return -1;
	}

	public static List<KiroEvent> parseStream(InputStream input) throws Exception {
		KiroEventParser parser = new KiroEventParser();
		List<KiroEvent> allEvents = new ArrayList<>();
		byte[] buf = new byte[8192];
		int read;
		while ((read = input.read(buf)) != -1) {
			byte[] chunk = new byte[read];
			System.arraycopy(buf, 0, chunk, 0, read);
			allEvents.addAll(parser.feed(chunk));
		}
		return allEvents;
	}
}
