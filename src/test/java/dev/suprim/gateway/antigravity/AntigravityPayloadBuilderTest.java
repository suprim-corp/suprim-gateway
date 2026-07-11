package dev.suprim.gateway.antigravity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityPayloadBuilderTest {

	@Test
	void build_simpleUserMessage() {
		Map<String, Object> request = Map.of(
				"model", "gemini-2.5-flash",
				"messages", List.of(
						Map.of("role", "user", "content", "Hello")
				)
		);

		String json = AntigravityPayloadBuilder.build(request, "projects/test-123");

		assertTrue(json.contains("\"contents\""));
		assertTrue(json.contains("\"role\":\"user\""));
		assertTrue(json.contains("\"text\":\"Hello\""));
		assertTrue(json.contains("\"project\":\"projects/test-123\""));
	}

	@Test
	void build_systemMessage_becomesSytemInstruction() {
		Map<String, Object> request = Map.of(
				"model", "gemini-2.5-flash",
				"messages", List.of(
						Map.of("role", "system", "content", "You are helpful"),
						Map.of("role", "user", "content", "Hi")
				)
		);

		String json = AntigravityPayloadBuilder.build(request, "projects/p1");

		assertTrue(json.contains("\"systemInstruction\""));
		assertTrue(json.contains("You are helpful"));
		assertFalse(json.contains("\"role\":\"system\""));
	}

	@Test
	void build_assistantRole_mappedToModel() {
		Map<String, Object> request = Map.of(
				"model", "gemini-2.5-flash",
				"messages", List.of(
						Map.of("role", "user", "content", "Hi"),
						Map.of("role", "assistant", "content", "Hello!"),
						Map.of("role", "user", "content", "How are you?")
				)
		);

		String json = AntigravityPayloadBuilder.build(request, "projects/p1");

		assertTrue(json.contains("\"role\":\"model\""));
		assertFalse(json.contains("\"role\":\"assistant\""));
	}

	@Test
	void build_passesMaxTokens() {
		Map<String, Object> request = Map.of(
				"model", "gemini-2.5-flash",
				"messages", List.of(Map.of("role", "user", "content", "Hi")),
				"max_tokens", 1024
		);

		String json = AntigravityPayloadBuilder.build(request, "projects/p1");

		assertTrue(json.contains("\"maxOutputTokens\":1024"));
	}

	@Test
	void build_defaultMaxTokensWhenNotProvided() {
		Map<String, Object> request = Map.of(
				"model", "gemini-2.5-flash",
				"messages", List.of(Map.of("role", "user", "content", "Hi"))
		);

		String json = AntigravityPayloadBuilder.build(request, "projects/p1");

		assertTrue(json.contains("\"maxOutputTokens\":65536"));
	}

	@Test
	void build_passesTemperatureAndTopP() {
		Map<String, Object> request = Map.of(
				"model", "gemini-2.5-flash",
				"messages", List.of(Map.of("role", "user", "content", "Hi")),
				"temperature", 0.7,
				"top_p", 0.9
		);

		String json = AntigravityPayloadBuilder.build(request, "projects/p1");

		assertTrue(json.contains("\"temperature\":0.7"));
		assertTrue(json.contains("\"topP\":0.9"));
	}
}
