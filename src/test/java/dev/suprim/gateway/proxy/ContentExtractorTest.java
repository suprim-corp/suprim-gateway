package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentExtractorTest {

	@Test
	void extractImages_claudeFormat() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "text", "text", "describe this"),
				Map.of("type", "image", "source", Map.of(
						"type", "base64",
						"media_type", "image/png",
						"data", "iVBORw0KGgo="
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertEquals(1, images.size());
		assertEquals("png", images.getFirst().format());
		assertEquals("iVBORw0KGgo=", images.getFirst().bytes());
	}

	@Test
	void extractImages_inputImageFormat() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "input_image", "source", Map.of(
						"type", "base64",
						"media_type", "image/jpeg",
						"data", "AAAA"
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertEquals(1, images.size());
		assertEquals("jpeg", images.getFirst().format());
		assertEquals("AAAA", images.getFirst().bytes());
	}

	@Test
	void extractImages_imageUrlDataUrl() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image_url", "image_url", Map.of(
						"url", "data:image/webp;base64,UklGR="
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertEquals(1, images.size());
		assertEquals("webp", images.getFirst().format());
		assertEquals("UklGR=", images.getFirst().bytes());
	}

	@Test
	void extractImages_noImages() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "text", "text", "hello")
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void extractImages_stringContent() {
		Message msg = Message.of("user", "plain text");

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void fromMessage_withImageBlocks_onlyExtractsText() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "text", "text", "describe this"),
				Map.of("type", "image", "source", Map.of(
						"type", "base64",
						"media_type", "image/png",
						"data", "iVBORw0KGgo="
				))
		));

		String text = ContentExtractor.fromMessage(msg);

		assertEquals("describe this", text);
	}

	@Test
	void hasImageBlock_true() {
		List<?> content = List.of(
				Map.of("type", "text", "text", "hi"),
				Map.of("type", "image", "source", Map.of())
		);

		assertTrue(ContentExtractor.hasImageBlock(content));
	}

	@Test
	void hasImageBlock_false() {
		List<?> content = List.of(
				Map.of("type", "text", "text", "hi")
		);

		assertFalse(ContentExtractor.hasImageBlock(content));
	}

	@Test
	void extractImages_defaultsToMediaTypePng() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image", "source", Map.of(
						"type", "base64",
						"data", "ABCD"
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertEquals(1, images.size());
		assertEquals("png", images.getFirst().format());
	}

	@Test
	void extractImages_mediaTypeKey() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image", "source", Map.of(
						"type", "base64",
						"mediaType", "image/gif",
						"data", "R0lG"
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertEquals(1, images.size());
		assertEquals("gif", images.getFirst().format());
	}

	@Test
	void extractImages_nullSource() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image")
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void extractImages_nullData() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image", "source", Map.of("type", "base64"))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void extractImages_imageUrlNullUrl() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image_url", "image_url", Map.of("detail", "high"))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void extractImages_imageUrlNullImageUrl() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image_url")
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void extractImages_imageUrlNotDataUrl() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image_url", "image_url", Map.of(
						"url", "https://example.com/img.png"
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void extractImages_imageUrlMalformedDataUrl() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image_url", "image_url", Map.of(
						"url", "data:image/pngbase64ABC"
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void fromMessage_stringContent() {
		Message msg = Message.of("user", "hello world");

		assertEquals("hello world", ContentExtractor.fromMessage(msg));
	}

	@Test
	void fromMessage_nullContent() {
		Message msg = Message.builder().role("user").build();

		assertEquals("", ContentExtractor.fromMessage(msg));
	}

	@Test
	void fromMessage_nonStringNonList() {
		Message msg = Message.of("user", 42);

		assertEquals("", ContentExtractor.fromMessage(msg));
	}

	@Test
	void fromResponsesBlock_string() {
		assertEquals("hello", ContentExtractor.fromResponsesBlock("hello"));
	}

	@Test
	void fromResponsesBlock_null() {
		assertEquals("", ContentExtractor.fromResponsesBlock(null));
	}

	@Test
	void fromResponsesBlock_nonStringNonList() {
		assertEquals("123", ContentExtractor.fromResponsesBlock(123));
	}

	@Test
	void fromResponsesBlock_list() {
		List<Map<String, Object>> content = List.of(
				Map.of("type", "input_text", "text", "hello "),
				Map.of("type", "text", "text", "world"),
				Map.of("type", "image", "data", "ignored")
		);

		assertEquals("hello world", ContentExtractor.fromResponsesBlock(content));
	}

	@Test
	void fromResponsesBlock_listWithNullText() {
		List<Map<String, Object>> content = List.of(
				Map.of("type", "text")
		);

		assertEquals("", ContentExtractor.fromResponsesBlock(content));
	}

	@Test
	void fromResponsesBlock_listWithNullType() {
		List<Map<String, Object>> content = List.of(
				Map.of("text", "ignored")
		);

		assertEquals("", ContentExtractor.fromResponsesBlock(content));
	}

	@Test
	void extractImages_nonMapItemInList() {
		Message msg = Message.of("user", List.of("not a map", Map.of("type", "text", "text", "hi")));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void fromMessage_nonMapItemInList() {
		Message msg = Message.of("user", List.of("not a map", Map.of("type", "text", "text", "hi")));

		assertEquals("hi", ContentExtractor.fromMessage(msg));
	}

	@Test
	void extractImages_imageUrlDataUrlCommaBeforeSemicolon() {
		Message msg = Message.of("user", List.of(
				Map.of("type", "image_url", "image_url", Map.of(
						"url", "data:image/png,foo;bar"
				))
		));

		List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(msg);

		assertTrue(images.isEmpty());
	}

	@Test
	void hasImageBlock_inputImageType() {
		List<?> content = List.of(
				Map.of("type", "input_image", "source", Map.of())
		);

		assertTrue(ContentExtractor.hasImageBlock(content));
	}

	@Test
	void hasImageBlock_nonMapItem() {
		List<?> content = List.of("not a map", Map.of("type", "text"));

		assertFalse(ContentExtractor.hasImageBlock(content));
	}

	@Test
	void hasImageBlock_imageUrlType() {
		List<?> content = List.of(
				Map.of("type", "image_url")
		);

		assertTrue(ContentExtractor.hasImageBlock(content));
	}

	@Test
	void fromResponsesBlock_nonMapItemInList() {
		List<Object> content = List.of("not a map", Map.of("type", "text", "text", "hi"));

		assertEquals("hi", ContentExtractor.fromResponsesBlock(content));
	}
}
