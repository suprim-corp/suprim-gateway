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
	void parseChunk_functionCall() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"Hanoi"}}}],"role":"model"}}]}}""";

		AntigravityStreamConverter.ParsedChunk parsed = AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertNull(parsed.text());
		assertNotNull(parsed.functionCall());
		assertEquals("get_weather", parsed.functionCall().name());
		assertTrue(parsed.functionCall().args().contains("Hanoi"));
	}

	@Test
	void parseChunk_textContent() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"}}]}}""";

		AntigravityStreamConverter.ParsedChunk parsed = AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertEquals("Hello", parsed.text());
		assertNull(parsed.functionCall());
		assertFalse(parsed.finished());
	}

	@Test
	void parseChunk_finishedWithNoText_returnsFinished() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}]}}""";

		AntigravityStreamConverter.ParsedChunk parsed = AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertNull(parsed.text());
		assertNull(parsed.functionCall());
		assertTrue(parsed.finished());
	}

	@Test
	void parseChunk_readsUsageAlongsideText() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"}}],
				"usageMetadata":{"promptTokenCount":1234,"candidatesTokenCount":56,
				"totalTokenCount":1290,"thoughtsTokenCount":40}}}""";

		AntigravityStreamConverter.ParsedChunk parsed =
				AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertEquals("Hi", parsed.text());
		assertEquals(1234, parsed.usage().promptTokens());
		assertEquals(56, parsed.usage().completionTokens());
		assertEquals(1290, parsed.usage().totalTokens());
		assertEquals(40, parsed.usage().thoughtsTokens());
	}

	@Test
	void parseChunk_readsUsageFromFinalChunkWithoutCandidates() {
		String geminiData = """
				{"response":{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2}}}""";

		AntigravityStreamConverter.ParsedChunk parsed =
				AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertNull(parsed.text());
		assertEquals(10, parsed.usage().promptTokens());
		assertEquals(2, parsed.usage().completionTokens());
	}

	@Test
	void parseChunk_keepsUsageOnFinishedChunk() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}],
				"usageMetadata":{"candidatesTokenCount":7}}}""";

		AntigravityStreamConverter.ParsedChunk parsed =
				AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertTrue(parsed.finished());
		assertEquals(7, parsed.usage().completionTokens());
	}

	@Test
	void parseChunk_readsConsumedCreditsFromWrapper() {
		String geminiData = """
				{"consumedCredits":{"creditType":"GOOGLE_ONE_AI","creditAmount":"3"},
				"response":{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"}}]}}""";

		AntigravityStreamConverter.ParsedChunk parsed =
				AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertEquals(3.0, parsed.consumedCredits());
	}

	@Test
	void parseChunk_leavesUsageAndCreditsNullWhenNotReported() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"}}]}}""";

		AntigravityStreamConverter.ParsedChunk parsed =
				AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertNull(parsed.usage());
		assertNull(parsed.consumedCredits());
	}

	@Test
	void parseChunk_ignoresUsageMetadataWithNoCounts() {
		String geminiData = """
				{"response":{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"}}],
				"usageMetadata":{"trafficType":"ON_DEMAND"}}}""";

		AntigravityStreamConverter.ParsedChunk parsed =
				AntigravityStreamConverter.parseChunk(geminiData);

		assertNotNull(parsed);
		assertNull(parsed.usage());
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
