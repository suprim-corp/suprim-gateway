package dev.suprim.gateway.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ContentExtractor {

	private ContentExtractor() {}

	record KiroImage(String format, String bytes) {}

	static String fromMessage(Message msg) {
		Object content = msg.content();
		if (content instanceof String s) return s;
		if (content instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			for (Object item : list) {
				if (item instanceof Map<?, ?> m) {
					if ("text".equals(m.get("type"))) sb.append(m.get("text"));
				}
			}
			return sb.toString();
		}
		return "";
	}

	static List<KiroImage> extractImages(Message msg) {
		Object content = msg.content();
		if (!(content instanceof List<?> list)) return List.of();

		List<KiroImage> images = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> m)) continue;
			String type = Optional.ofNullable(m.get("type"))
			                      .map(Object::toString)
			                      .orElse("");

			if ("image".equals(type) || "input_image".equals(type)) {
				if (!(m.get("source") instanceof Map<?, ?> source)) continue;
				String data = Optional.ofNullable(source.get("data"))
				                      .map(Object::toString)
				                      .orElse(null);
				if (data == null) continue;
				String mediaType = Optional.ofNullable(source.get("media_type"))
				                           .or(() -> Optional.ofNullable(
								                           source.get("mediaType")
						                           )
				                           )
				                           .map(Object::toString)
				                           .orElse("image/png");
				String format = mediaType.replaceFirst("^image/", "");
				images.add(new KiroImage(format, data));
			} else if ("image_url".equals(type)) {
				if (!(m.get("image_url") instanceof Map<?, ?> imageUrl))
					continue;
				String url = Optional.ofNullable(imageUrl.get("url"))
				                     .map(Object::toString)
				                     .orElse(null);
				if (url == null) continue;
				if (url.startsWith("data:image/")) {
					int semicolon = url.indexOf(';');
					int comma = url.indexOf(',');
					if (semicolon > 0 && comma > semicolon) {
						String format = url.substring(11, semicolon);
						String data = url.substring(comma + 1);
						images.add(new KiroImage(format, data));
					}
				}
			}
		}
		return images;
	}

	public static boolean hasImageBlock(List<?> list) {
		for (Object item : list) {
			if (item instanceof Map<?, ?> m) {
				String type = Optional.ofNullable(m.get("type"))
				                      .map(Object::toString)
				                      .orElse("");
				if ("image".equals(type) || "image_url".equals(type) ||
				    "input_image".equals(type)) {
					return true;
				}
			}
		}
		return false;
	}

	static String fromResponsesBlock(Object content) {
		if (content instanceof String s) return s;
		if (content instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			for (Object block : list) {
				if (block instanceof Map<?, ?> m) {
					String bType = Optional.ofNullable(m.get("type"))
					                       .map(Object::toString)
					                       .orElse("");
					if ("input_text".equals(bType) || "text".equals(bType)) {
						Object text = m.get("text");
						if (text != null) sb.append(text);
					}
				}
			}
			return sb.toString();
		}
		return content != null ? content.toString() : "";
	}
}
