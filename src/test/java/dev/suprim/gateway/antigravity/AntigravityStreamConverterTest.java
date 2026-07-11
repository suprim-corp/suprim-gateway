package dev.suprim.gateway.antigravity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityStreamConverterTest {

	@Test
	void convertChunk_textContent() {
		String geminiData = """
				{"candidates":[{"content":{"parts":[{"text":"Hello world"}],"role":"model"}}]}""";

		String openAiChunk = AntigravityStreamConverter.convertChunk(geminiData, "gemini-2.5-flash", "chatcmpl-123");

		assertTrue(openAiChunk.contains("\"delta\":{\"content\":\"Hello world\"}"));
		assertTrue(openAiChunk.contains("\"model\":\"gemini-2.5-flash\""));
		assertTrue(openAiChunk.contains("\"id\":\"chatcmpl-123\""));
		assertTrue(openAiChunk.contains("\"object\":\"chat.completion.chunk\""));
	}

	@Test
	void convertChunk_finishReasonStop() {
		String geminiData = """
				{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}]}""";

		String openAiChunk = AntigravityStreamConverter.convertChunk(geminiData, "gemini-2.5-flash", "chatcmpl-123");

		assertTrue(openAiChunk.contains("\"finish_reason\":\"stop\""));
	}

	@Test
	void convertChunk_finishReasonMaxTokens() {
		String geminiData = """
				{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"MAX_TOKENS"}]}""";

		String openAiChunk = AntigravityStreamConverter.convertChunk(geminiData, "gemini-2.5-flash", "id-1");

		assertTrue(openAiChunk.contains("\"finish_reason\":\"length\""));
	}

	@Test
	void convertChunk_finishReasonSafety() {
		String geminiData = """
				{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"SAFETY"}]}""";

		String openAiChunk = AntigravityStreamConverter.convertChunk(geminiData, "gemini-2.5-flash", "id-1");

		assertTrue(openAiChunk.contains("\"finish_reason\":\"content_filter\""));
	}

	@Test
	void convertChunk_noFinishReason_null() {
		String geminiData = """
				{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"}}]}""";

		String openAiChunk = AntigravityStreamConverter.convertChunk(geminiData, "gemini-2.5-flash", "id-1");

		assertTrue(openAiChunk.contains("\"finish_reason\":null"));
	}

	@Test
	void convertChunk_emptyCandidates_returnsNull() {
		String geminiData = """
				{"candidates":[]}""";

		String result = AntigravityStreamConverter.convertChunk(geminiData, "gemini-2.5-flash", "id-1");

		assertNull(result);
	}

	@Test
	void buildStopChunk_correctFormat() {
		String chunk = AntigravityStreamConverter.buildStopChunk("gemini-2.5-flash", "chatcmpl-456");

		assertTrue(chunk.contains("\"finish_reason\":\"stop\""));
		assertTrue(chunk.contains("\"delta\":{}"));
		assertTrue(chunk.contains("\"id\":\"chatcmpl-456\""));
	}

	@Test
	void buildDoneEvent_correctFormat() {
		String done = AntigravityStreamConverter.buildDoneEvent();

		assertEquals("data: [DONE]\n\n", done);
	}
}
