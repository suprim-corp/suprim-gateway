package dev.suprim.gateway.provider.antigravity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a message's content into Gemini {@code parts}.
 * <p>
 * Content arrives either as a plain String or as an array of content blocks in
 * OpenAI or Anthropic shape. Media blocks become {@code inlineData} parts; text
 * blocks become {@code text} parts. Serializing an array with {@code toString()}
 * instead ships the base64 payload as prose, which the upstream rejects with
 * 400 INVALID_ARGUMENT.
 */
final class AntigravityContentParts {

	private static final JsonMapper MAPPER = new JsonMapper();

	private AntigravityContentParts() {}

	/**
	 * Appends every convertible block of {@code content} to {@code parts}.
	 * Unrecognized blocks are dropped rather than stringified, since a JSON dump
	 * of a media block is never useful to the model.
	 */
	static void append(ArrayNode parts, Object content) {
		JsonNode node = toNode(content);
		if (node == null) {
			return;
		}
		if (node.isString()) {
			addText(parts, node.stringValue());
			return;
		}
		if (!node.isArray()) {
			addText(parts, node.toString());
			return;
		}
		for (JsonNode block : node) {
			appendBlock(parts, block);
		}
	}

	/**
	 * Concatenates only the text blocks of {@code content}, for the fields that
	 * are text-only upstream (system instruction, assistant summaries).
	 */
	static String text(Object content) {
		JsonNode node = toNode(content);
		if (node == null) {
			return "";
		}
		if (node.isString()) {
			return node.stringValue();
		}
		if (!node.isArray()) {
			return node.toString();
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode block : node) {
			if (block.isString()) {
				sb.append(block.stringValue());
			} else if (block.isObject() && isTextType(str(block, "type"))) {
				sb.append(str(block, "text"));
			}
		}
		return sb.toString();
	}

	private static JsonNode toNode(Object content) {
		return switch (content) {
			case null -> null;
			case String s -> MAPPER.getNodeFactory().stringNode(s);
			case JsonNode node -> node;
			default -> MAPPER.valueToTree(content);
		};
	}

	private static void appendBlock(ArrayNode parts, JsonNode block) {
		if (block.isString()) {
			addText(parts, block.stringValue());
			return;
		}
		if (!block.isObject()) {
			return;
		}
		String type = str(block, "type");
		if (isTextType(type)) {
			addText(parts, str(block, "text"));
			return;
		}
		switch (type) {
			case "image_url" -> addDataUrl(
					parts,
					str(block.path("image_url"), "url")
			);
			case "input_audio" -> addInline(
					parts,
					audioMime(str(block.path("input_audio"), "format")),
					str(block.path("input_audio"), "data")
			);
			case "file" -> addDataUrl(
					parts,
					str(block.path("file"), "file_data")
			);
			default -> appendSourceBlock(parts, block, type);
		}
	}

	/**
	 * Handles Anthropic-shaped media blocks, where the payload sits under
	 * {@code source} as either inline base64 or a data URL.
	 */
	private static void appendSourceBlock(
			ArrayNode parts,
			JsonNode block,
			String type
	) {
		JsonNode source = block.path("source");
		if (!source.isObject()) {
			return;
		}
		String data = str(source, "data");
		if (data.isEmpty()) {
			addDataUrl(parts, str(source, "url"));
			return;
		}
		String mime = str(source, "media_type");
		if (mime.isEmpty()) {
			mime = str(source, "mediaType");
		}
		addInline(parts, mime.isEmpty() ? defaultMime(type) : mime, data);
	}

	private static void addDataUrl(ArrayNode parts, String url) {
		if (!url.startsWith("data:")) {
			return;
		}
		int semicolon = url.indexOf(';');
		int comma = url.indexOf(',');
		if (semicolon < 5 || comma <= semicolon) {
			return;
		}
		addInline(
				parts,
				url.substring(5, semicolon),
				url.substring(comma + 1)
		);
	}

	private static void addInline(ArrayNode parts, String mime, String data) {
		if (mime.isEmpty() || data.isEmpty()) {
			return;
		}
		ObjectNode inline = parts.addObject().putObject("inlineData");
		inline.put("mimeType", mime);
		inline.put("data", data);
	}

	private static void addText(ArrayNode parts, String text) {
		if (text.isEmpty()) {
			return;
		}
		parts.addObject().put("text", text);
	}

	private static boolean isTextType(String type) {
		return "text".equals(type) || "input_text".equals(type) ||
		       "output_text".equals(type);
	}

	private static String audioMime(String format) {
		// OpenAI sends a bare container name ("wav", "mp3"); Gemini wants a MIME type.
		return format.isEmpty() ? "audio/wav" : "audio/" + format;
	}

	private static String defaultMime(String type) {
		return switch (type) {
			case "image", "input_image" -> "image/png";
			case "audio", "input_audio" -> "audio/wav";
			case "document" -> "application/pdf";
			default -> "";
		};
	}

	private static String str(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.stringValue() : "";
	}
}
