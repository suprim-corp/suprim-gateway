package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
	void build_carriesRequestIdWhenGiven() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(Message.of("user", "Hello")))
				.build();

		String json = AntigravityPayloadBuilder.build(
				request, "gemini-2.5-flash", "projects/p1", Map.of(), "req-abc"
		);

		assertTrue(json.contains("\"requestId\":\"req-abc\""));
	}

	@Test
	void build_omitsRequestIdWhenAbsent() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(Message.of("user", "Hello")))
				.build();

		assertFalse(
				AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1")
				                         .contains("requestId")
		);
		assertFalse(
				AntigravityPayloadBuilder.build(
						request, "gemini-2.5-flash", "projects/p1", Map.of(), ""
				).contains("requestId")
		);
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
	void build_mergesAllSystemMessagesAndStripsClaudeDefaultOnlyForClaude() {
		InternalRequest request = InternalRequest.builder()
				.model("claude-sonnet")
				.messages(List.of(
						Message.of(
								"system",
								"You are Claude Code, Anthropic's official CLI for Claude.\n" +
								"You are an interactive agent that helps users with software engineering tasks."
						),
						Message.of("system", "Keep this instruction"),
						Message.of("user", "Hi")
				))
				.build();

		String claude = AntigravityPayloadBuilder.build(request, "claude-sonnet", "projects/p1");
		String gemini = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");

		assertFalse(claude.contains("You are Claude Code"));
		assertTrue(claude.contains("Keep this instruction"));
		assertTrue(gemini.contains("You are Claude Code"));
	}

	@Test
	void build_preservesCustomTextContainingOrFollowingDefaultMarker() {
		InternalRequest request = InternalRequest.builder()
				.model("claude-sonnet")
				.messages(List.of(
						Message.of("system", "Discuss the phrase You are Claude Code"),
						Message.of(
								"system",
								"You are Claude Code, Anthropic's official CLI for Claude. Always answer in French"
						),
						Message.of("user", "Hi")
				))
				.build();

		String json = AntigravityPayloadBuilder.build(request, "claude-sonnet", "projects/p1");
		assertTrue(json.contains("Discuss the phrase You are Claude Code"));
		assertTrue(json.contains("Always answer in French"));
	}

	@Test
	void build_mergesMultipleSystemMessages() {
		InternalRequest request = InternalRequest.builder()
				.model("gemini-2.5-flash")
				.messages(List.of(
						Message.of("system", "first"),
						Message.of("system", "second"),
						Message.of("user", "Hi")
				))
				.build();

		String json = AntigravityPayloadBuilder.build(request, "gemini-2.5-flash", "projects/p1");
		assertTrue(json.indexOf("first") < json.indexOf("second"));
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
