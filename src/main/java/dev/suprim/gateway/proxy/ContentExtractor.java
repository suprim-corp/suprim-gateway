package dev.suprim.gateway.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ContentExtractor {

	private ContentExtractor() {}

	record KiroImage(String format, String bytes) {}

	static String fromMessage(Map<String, Object> msg) {
		Object content = msg.get("content");
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

	@SuppressWarnings("unchecked")
	static List<KiroImage> extractImages(Map<String, Object> msg) {
		Object content = msg.get("content");
		if (!(content instanceof List<?> list)) return List.of();

		List<KiroImage> images = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> m)) continue;
			String type = (String) m.get("type");

			if ("image".equals(type) || "input_image".equals(type)) {
				Map<String, Object> source = (Map<String, Object>) m.get(
						"source");
				if (source == null) continue;
				String data = (String) source.get("data");
				if (data == null) continue;
				String mediaType = (String) source.get("media_type");
				if (mediaType == null) mediaType = (String) source.get(
						"mediaType");
				if (mediaType == null) mediaType = "image/png";
				String format = mediaType.replaceFirst("^image/", "");
				images.add(new KiroImage(format, data));
			} else if ("image_url".equals(type)) {
				Map<String, Object> imageUrl = (Map<String, Object>) m.get(
						"image_url");
				if (imageUrl == null) continue;
				String url = (String) imageUrl.get("url");
				if (url == null) continue;
				// data:image/png;base64,<data>
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

	static String fromResponsesBlock(Object content) {
		if (content instanceof String s) return s;
		if (content instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			for (Object block : list) {
				if (block instanceof Map<?, ?> m) {
					Object typeObj = m.get("type");
					String bType = typeObj != null ? typeObj.toString() : "";
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
