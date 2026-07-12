package dev.suprim.gateway.provider.antigravity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityStreamConverterTest {

	@Test
	void extractText_fromResponseWrapper() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"text":"Hello world"}],"role":"model"}}]}}""";

		String text = AntigravityStreamConverter.extractText(geminiData);

		assertEquals("Hello world", text);
	}

	@Test
	void extractText_fromRootCandidates() {
		String geminiData = """
				{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"}}]}""";

		String text = AntigravityStreamConverter.extractText(geminiData);

		assertEquals("Hi", text);
	}

	@Test
	void extractText_emptyTextWithFinishReason_returnsNull() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}]}}""";

		String text = AntigravityStreamConverter.extractText(geminiData);

		assertNull(text);
	}

	@Test
	void extractText_emptyCandidates_returnsNull() {
		String geminiData = """
				{"response":{"candidates":[]}}""";

		String result = AntigravityStreamConverter.extractText(geminiData);

		assertNull(result);
	}

	@Test
	void extractText_noParts_returnsNull() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"role":"model"}}]}}""";

		String result = AntigravityStreamConverter.extractText(geminiData);

		assertNull(result);
	}

	@Test
	void buildChunkPublic_correctFormat() {
		String chunk = AntigravityStreamConverter.buildChunkPublic("chatcmpl-123", "gemini-2.5-flash", "Hello");

		assertTrue(chunk.contains("\"delta\":{\"content\":\"Hello\"}"));
		assertTrue(chunk.contains("\"model\":\"gemini-2.5-flash\""));
		assertTrue(chunk.contains("\"id\":\"chatcmpl-123\""));
		assertTrue(chunk.contains("\"finish_reason\":null"));
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
