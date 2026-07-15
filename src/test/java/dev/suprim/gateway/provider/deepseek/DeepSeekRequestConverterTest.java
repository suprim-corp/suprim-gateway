package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekRequestConverterTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void convertBasicRequest() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "Hello")))
				.stream(true)
				.temperature(0.7)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "session-123");
		JsonNode node = parse(payload);

		assertEquals("session-123", node.get("chat_session_id").asText());
		assertEquals("Hello", node.get("prompt").asText());
		assertEquals(0, node.get("parent_message_id").asInt());
		assertEquals("default", node.get("model_type").asText());
		assertTrue(node.get("thinking_enabled").asBoolean());
		assertFalse(node.get("search_enabled").asBoolean());
		assertEquals(0.7, node.get("temperature").asDouble(), 0.001);
	}

	@Test
	void modelMapping_v4Pro() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-pro")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s1");
		JsonNode node = parse(payload);

		assertEquals("default", node.get("model_type").asText());
	}

	@Test
	void modelMapping_v4FlashSearch() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash-search")
				.messages(List.of(Message.of("user", "search this")))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s2");
		JsonNode node = parse(payload);

		assertEquals("default", node.get("model_type").asText());
		assertTrue(node.get("search_enabled").asBoolean());
	}

	@Test
	void modelMapping_v4ProSearch() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-pro-search")
				.messages(List.of(Message.of("user", "find info")))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s3");
		JsonNode node = parse(payload);

		assertEquals("default", node.get("model_type").asText());
		assertTrue(node.get("search_enabled").asBoolean());
	}

	@Test
	void modelMapping_v4Vision() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-vision")
				.messages(List.of(Message.of("user", "describe image")))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s4");
		JsonNode node = parse(payload);

		assertEquals("vision", node.get("model_type").asText());
	}

	@Test
	void multipleMessages_flattenedToPrompt() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(
						Message.of("system", "You are helpful"),
						Message.of("user", "Hi"),
						Message.of("assistant", "Hello!"),
						Message.of("user", "How are you?")
				))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s5");
		JsonNode node = parse(payload);

		String prompt = node.get("prompt").asText();
		assertTrue(prompt.contains("You are helpful"));
		assertTrue(prompt.contains("Hi"));
		assertTrue(prompt.contains("Hello!"));
		assertTrue(prompt.contains("How are you?"));
	}

	@Test
	void temperatureNull_usesDefault() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.temperature(null)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s6");
		JsonNode node = parse(payload);

		assertTrue(node.has("temperature"));
	}

	@Test
	void thinkingEnabled_forThinkModels() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash-think")
				.messages(List.of(Message.of("user", "reason about this")))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s7");
		JsonNode node = parse(payload);

		assertTrue(node.get("thinking_enabled").asBoolean());
	}

	@Test
	void modelWithoutPrefix_handledGracefully() {
		InternalRequest request = InternalRequest.builder()
				.model("v4-flash")
				.messages(List.of(Message.of("user", "no prefix")))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s8");
		JsonNode node = parse(payload);

		assertEquals("default", node.get("model_type").asText());
	}

	@Test
	void nullMessages_emptyPrompt() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(null)
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s9");
		JsonNode node = parse(payload);

		assertEquals("", node.get("prompt").asText());
	}

	@Test
	void emptyMessages_emptyPrompt() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of())
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s10");
		JsonNode node = parse(payload);

		assertEquals("", node.get("prompt").asText());
	}

	@Test
	void nullContentInMessage_skipped() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(
						Message.builder().role("user").content(null).build(),
						Message.of("user", "real content")
				))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s11");
		JsonNode node = parse(payload);

		String prompt = node.get("prompt").asText();
		assertTrue(prompt.contains("real content"));
		assertFalse(prompt.contains("null"));
	}

	@Test
	void nonStringContent_usesToString() {
		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", List.of("part1", "part2"))))
				.stream(true)
				.build();

		String payload = DeepSeekRequestConverter.convert(request, "s12");
		JsonNode node = parse(payload);

		String prompt = node.get("prompt").asText();
		assertTrue(prompt.contains("part1"));
		assertTrue(prompt.contains("part2"));
	}

	private JsonNode parse(String json) {
		try {
			return JSON.readTree(json);
		} catch (Exception e) {
			fail("Invalid JSON: " + e.getMessage());
			return null;
		}
	}
}
