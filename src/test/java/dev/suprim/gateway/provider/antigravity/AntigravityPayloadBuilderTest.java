package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityPayloadBuilderTest {

	@Test
	void build_simpleUserMessage() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(Message.of("user", "Hello")))
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/test-123");

		assertTrue(json.contains("\"request\""));
		assertTrue(json.contains("\"contents\""));
		assertTrue(json.contains("\"role\":\"user\""));
		assertTrue(json.contains("\"text\":\"Hello\""));
		assertTrue(json.contains("\"project\":\"projects/test-123\""));
		assertTrue(json.contains("\"model\":\"gemini-2.5-flash\""));
		assertTrue(json.contains("\"userAgent\":\"antigravity\""));
	}

	@Test
	void build_systemMessage_becomesSystemInstruction() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(
						Message.of("system", "You are helpful"),
						Message.of("user", "Hi")
				))
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");

		assertTrue(json.contains("\"systemInstruction\""));
		assertTrue(json.contains("You are helpful"));
		assertFalse(json.contains("\"role\":\"system\""));
	}

	@Test
	void build_assistantRole_mappedToModel() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(
						Message.of("user", "Hi"),
						Message.of("assistant", "Hello!"),
						Message.of("user", "How are you?")
				))
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");

		assertTrue(json.contains("\"role\":\"model\""));
		assertFalse(json.contains("\"role\":\"assistant\""));
	}

	@Test
	void build_passesMaxTokens() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(Message.of("user", "Hi")))
				.maxTokens(1024)
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");

		assertTrue(json.contains("\"maxOutputTokens\":1024"));
	}

	@Test
	void build_defaultMaxTokensWhenNotProvided() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(Message.of("user", "Hi")))
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");

		assertTrue(json.contains("\"maxOutputTokens\":65536"));
	}

	@Test
	void build_passesTemperature() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(Message.of("user", "Hi")))
				.temperature(0.7)
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");

		assertTrue(json.contains("\"temperature\":0.7"));
	}

	@Test
	void build_contentInsideRequestWrapper() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(Message.of("user", "Hi")))
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");

		int requestIdx = json.indexOf("\"request\"");
		int contentsIdx = json.indexOf("\"contents\"");
		assertTrue(requestIdx < contentsIdx);
	}
}
