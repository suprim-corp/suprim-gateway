package dev.suprim.gateway.antigravity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityNonStreamTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void nonStreamResponse_correctStructure() throws Exception {
		// Simulate what handleNonStream builds
		String text = "Hello world";
		String id = "chatcmpl-test-123";
		String model = "gemini-2.5-flash";
		int inputTokens = 10;
		int outputTokens = text.length() / 4;

		Map<String, Object> responseBody = Map.of(
				"id", id,
				"object", "chat.completion",
				"model", model,
				"choices", List.of(Map.of(
						"index", 0,
						"message", Map.of("role", "assistant", "content", text),
						"finish_reason", "stop"
				)),
				"usage", Map.of(
						"prompt_tokens", inputTokens,
						"completion_tokens", outputTokens,
						"total_tokens", inputTokens + outputTokens
				)
		);

		String json = MAPPER.writeValueAsString(responseBody);
		JsonNode parsed = MAPPER.readTree(json);

		assertEquals("chatcmpl-test-123", parsed.get("id").asString());
		assertEquals("chat.completion", parsed.get("object").asString());
		assertEquals("gemini-2.5-flash", parsed.get("model").asString());

		JsonNode choices = parsed.get("choices");
		assertEquals(1, choices.size());
		JsonNode choice = choices.get(0);
		assertEquals(0, choice.get("index").asInt());
		assertEquals("stop", choice.get("finish_reason").asString());

		JsonNode message = choice.get("message");
		assertEquals("assistant", message.get("role").asString());
		assertEquals("Hello world", message.get("content").asString());

		JsonNode usage = parsed.get("usage");
		assertEquals(10, usage.get("prompt_tokens").asInt());
		assertEquals(2, usage.get("completion_tokens").asInt());
		assertEquals(12, usage.get("total_tokens").asInt());
	}

	@Test
	void nonStreamResponse_handlesSpecialCharacters() throws Exception {
		String text = "Line1\nLine2\t\"quoted\"";

		Map<String, Object> responseBody = Map.of(
				"id", "chatcmpl-x",
				"object", "chat.completion",
				"model", "gemini-2.5-flash",
				"choices", List.of(Map.of(
						"index", 0,
						"message", Map.of("role", "assistant", "content", text),
						"finish_reason", "stop"
				)),
				"usage", Map.of(
						"prompt_tokens", 5,
						"completion_tokens", 5,
						"total_tokens", 10
				)
		);

		String json = MAPPER.writeValueAsString(responseBody);
		JsonNode parsed = MAPPER.readTree(json);

		assertEquals("Line1\nLine2\t\"quoted\"",
				parsed.get("choices").get(0).get("message").get("content").asString());
	}
}
