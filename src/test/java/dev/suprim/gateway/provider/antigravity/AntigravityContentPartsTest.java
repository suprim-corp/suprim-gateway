package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntigravityContentPartsTest {

	private static String build(Object content) {
		return AntigravityPayloadBuilder.build(
				InternalRequest.builder()
				               .model("gemini-2.5-flash")
				               .messages(List.of(Message.of("user", content)))
				               .build(),
				"gemini-2.5-flash",
				"projects/p1"
		);
	}

	@Test
	void openAiAudioBecomesInlineData() {
		String json = build(List.of(
				Map.of("type", "text", "text", "transcribe this"),
				Map.of(
						"type", "input_audio",
						"input_audio", Map.of("format", "wav", "data", "UklGRg==")
				)
		));

		assertTrue(json.contains("\"mimeType\":\"audio/wav\""));
		assertTrue(json.contains("\"data\":\"UklGRg==\""));
		assertTrue(json.contains("\"text\":\"transcribe this\""));
		assertFalse(json.contains("input_audio"));
	}

	@Test
	void anthropicAudioSourceBecomesInlineData() {
		String json = build(List.of(Map.of(
				"type", "audio",
				"source", Map.of(
						"type", "base64",
						"media_type", "audio/mpeg",
						"data", "SUQz"
				)
		)));

		assertTrue(json.contains("\"mimeType\":\"audio/mpeg\""));
		assertTrue(json.contains("\"data\":\"SUQz\""));
	}

	@Test
	void imageDataUrlBecomesInlineData() {
		String json = build(List.of(Map.of(
				"type", "image_url",
				"image_url", Map.of("url", "data:image/png;base64,iVBORw0=")
		)));

		assertTrue(json.contains("\"mimeType\":\"image/png\""));
		assertTrue(json.contains("\"data\":\"iVBORw0=\""));
	}

	@Test
	void plainStringStaysText() {
		assertTrue(build("Hello").contains("\"text\":\"Hello\""));
	}

	@Test
	void unknownBlockIsDroppedNotStringified() {
		String json = build(List.of(Map.of("type", "mystery", "blob", "xyz")));

		assertFalse(json.contains("mystery"));
		assertTrue(json.contains("\"parts\""));
	}

	@Test
	void assistantMediaCollapsesToTextOnly() {
		String json = AntigravityPayloadBuilder.build(
				InternalRequest.builder()
				               .model("gemini-2.5-flash")
				               .messages(List.of(Message.of("assistant", List.of(
						               Map.of("type", "text", "text", "said"),
						               Map.of(
								               "type", "image_url",
								               "image_url", Map.of(
										               "url",
										               "data:image/png;base64,AAA="
								               )
						               )
				               ))))
				               .build(),
				"gemini-2.5-flash",
				"projects/p1"
		);

		assertTrue(json.contains("\"text\":\"said\""));
		assertFalse(json.contains("inlineData"));
	}
}
