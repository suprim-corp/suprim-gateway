package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.proxy.ContentExtractor;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Appends image data to a message node in the upstream payload format.
 */
final class ImageAppender {

	private ImageAppender() {}

	/**
	 * Adds an {@code images} array to the given message node, each entry containing
	 * the image format and base64-encoded bytes from the source.
	 *
	 * @param message the message node to attach images to
	 * @param images  list of extracted images; no-op if null or empty
	 */
	static void append(
			ObjectNode message,
			List<ContentExtractor.KiroImage> images
	) {
		if (images == null || images.isEmpty()) {
			return;
		}

		ArrayNode imagesNode = message.putArray("images");

		for (ContentExtractor.KiroImage image : images) {
			ObjectNode imgNode = imagesNode.addObject();
			imgNode.put("format", image.format());
			ObjectNode source = imgNode.putObject("source");
			source.put("bytes", image.bytes());
		}
	}
}
