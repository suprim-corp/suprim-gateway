package dev.suprim.gateway.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class KiroFrameDecoder {

	private static final Logger log = LoggerFactory.getLogger(KiroFrameDecoder.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	private final StringBuilder textBuffer = new StringBuilder();
	private byte[] binaryBuffer = new byte[0];
	private Boolean isBinaryMode = null;

	public List<JsonNode> decode(byte[] chunk) {
		if (isBinaryMode == null) {
			isBinaryMode = chunk.length == 0 ||
			               (chunk[0] != '{' && chunk[0] != '[' &&
			                chunk[0] != '\n' && chunk[0] != '\r' &&
			                chunk[0] != ' ');
		}
		if (!isBinaryMode) {
			textBuffer.append(new String(chunk, StandardCharsets.UTF_8));
			return parseTextBuffer();
		}
		return decodeBinary(chunk);
	}

	private List<JsonNode> decodeBinary(byte[] chunk) {
		byte[] newBuf = new byte[binaryBuffer.length + chunk.length];
		System.arraycopy(binaryBuffer, 0, newBuf, 0, binaryBuffer.length);
		System.arraycopy(chunk, 0, newBuf, binaryBuffer.length, chunk.length);
		binaryBuffer = newBuf;

		List<JsonNode> nodes = new ArrayList<>();
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
				nodes.addAll(parseTextBuffer());
				return nodes;
			}
			if (binaryBuffer.length < totalLength) break;

			int headersLength = view.getInt(4);
			int headersEnd = 12 + headersLength;
			int payloadEnd = totalLength - 4;

			String eventType = extractEventType(
					binaryBuffer,
					12,
					headersLength
			);

			if (payloadEnd > headersEnd) {
				String text = new String(
						binaryBuffer,
						headersEnd,
						payloadEnd - headersEnd,
						StandardCharsets.UTF_8
				).trim();
				if (text.startsWith("{")) {
					try {
						JsonNode node = mapper.readTree(text);
						if (eventType != null && node.isObject()) {
							((tools.jackson.databind.node.ObjectNode) node)
									.put("__eventType", eventType);
						}
						nodes.add(node);
					} catch (Exception e) {
						textBuffer.append(text);
						nodes.addAll(parseTextBuffer());
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
		return nodes;
	}

	private static String extractEventType(
			byte[] buf,
			int offset,
			int headersLength
	) {
		int end = offset + headersLength;
		while (offset < end) {
			int nameLen = buf[offset] & 0xFF;
			offset++;
			if (offset + nameLen > end) break;
			String name = new String(
					buf,
					offset,
					nameLen,
					StandardCharsets.UTF_8
			);
			offset += nameLen;
			if (offset >= end) break;
			int valueType = buf[offset] & 0xFF;
			offset++;
			if (valueType == 7) { // String
				if (offset + 2 > end) break;
				int valueLen =
						((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
				offset += 2;
				if (offset + valueLen > end) break;
				String value = new String(
						buf,
						offset,
						valueLen,
						StandardCharsets.UTF_8
				);
				offset += valueLen;
				if (":event-type".equals(name)) return value;
			} else {
				int skip = switch (valueType) {
					case 0, 1 -> 0;
					case 2 -> 1;
					case 3 -> 2;
					case 4 -> 4;
					case 5, 8 -> 8;
					case 9 -> 16;
					case 6 -> (offset + 2 <= end)
							? 2 + (((buf[offset] & 0xFF) << 8) |
							       (buf[offset + 1] & 0xFF))
							: end - offset;
					default -> end - offset;
				};
				offset += skip;
			}
		}
		return null;
	}

	private List<JsonNode> parseTextBuffer() {
		List<JsonNode> nodes = new ArrayList<>();
		int searchFrom = 0;
		while (true) {
			int start = indexOf(textBuffer, '{', searchFrom);
			if (start == -1) break;
			int end = findMatchingBrace(textBuffer, start);
			if (end == -1) break;
			String jsonStr = textBuffer.substring(start, end + 1);
			searchFrom = end + 1;
			try {
				nodes.add(mapper.readTree(jsonStr));
			} catch (Exception e) {
				log.debug(
						"[Decoder] malformed JSON: {}",
						jsonStr.length() > 200 ? jsonStr.substring(
								0,
								200
						) : jsonStr
				);
			}
		}
		if (searchFrom > 0) textBuffer.delete(0, searchFrom);
		return nodes;
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
}
