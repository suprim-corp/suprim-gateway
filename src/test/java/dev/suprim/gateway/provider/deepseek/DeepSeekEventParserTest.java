package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekEventParserTest {

	@Test
	void parseContentChunk() {
		String sse = line("""
				{"v":"Hello world","p":"response/content"}""");
		DeepSeekEventParser.Result result = parse(sse + done());

		List<KiroEvent> events = result.events();
		assertEquals(1, events.size());
		assertEquals("content", events.getFirst().type());
		assertEquals("Hello world", events.getFirst().content());
	}

	@Test
	void parseThinkingContent() {
		String sse = line("""
				{"v":"Let me think...","p":"response/thinking_content"}""")
				+ line("""
				{"v":"The answer is 42","p":"response/content"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals(2, result.events().size());
		assertEquals("reasoning", result.events().getFirst().type());
		assertEquals("Let me think...", result.events().getFirst().content());
		assertEquals("content", result.events().get(1).type());
		assertEquals("The answer is 42", result.events().get(1).content());
	}

	@Test
	void parseStatusFinished() {
		String sse = line("""
				{"v":"hi","p":"response/content"}""")
				+ line("""
				{"v":"FINISHED","p":"response/status"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals("FINISHED", result.status());
	}

	@Test
	void parseStatusIncomplete() {
		String sse = line("""
				{"v":"partial","p":"response/content"}""")
				+ line("""
				{"v":"INCOMPLETE","p":"response/status"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals("INCOMPLETE", result.status());
	}

	@Test
	void parseStatusAutoContinue() {
		String sse = line("""
				{"v":"text","p":"response/content"}""")
				+ line("""
				{"v":"AUTO_CONTINUE","p":"response/status"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals("AUTO_CONTINUE", result.status());
	}

	@Test
	void parseTopLevelResponseMessageId() {
		String sse = line("""
				{"response_message_id":12345,"v":"ok","p":"response/content"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals(12345, result.responseMessageId());
	}

	@Test
	void parseNestedResponseMessageId() {
		String sse = line("""
				{"v":{"response":{"message_id":99999}},"p":"response"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals(99999, result.responseMessageId());
	}

	@Test
	void parseFragmentsWithTypes() {
		String sse = line("""
				{"v":[{"type":"THINKING","content":"deep thought"}],"p":"response/fragments","o":"APPEND"}""")
				+ line("""
				{"v":[{"type":"RESPONSE","content":"final answer"}],"p":"response/fragments","o":"APPEND"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals(2, result.events().size());
		assertEquals("reasoning", result.events().getFirst().type());
		assertEquals("deep thought", result.events().getFirst().content());
		assertEquals("content", result.events().get(1).type());
		assertEquals("final answer", result.events().get(1).content());
	}

	@Test
	void thinkTagTransitionsToContent() {
		String sse = line("""
				{"v":"thinking part</think>text part","p":"response/thinking_content"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		List<KiroEvent> events = result.events();
		assertEquals(1, events.size());
		assertEquals("reasoning", events.getFirst().type());
		assertEquals("thinking part", events.getFirst().content());
	}

	@Test
	void skipNonDataLines() {
		String sse = ": comment\n\nnot-data: foo\n\n"
				+ line("""
				{"v":"real","p":"response/content"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals(1, result.events().size());
		assertEquals("real", result.events().getFirst().content());
	}

	@Test
	void skipMalformedJson() {
		String sse = "data: {broken json\n\n"
				+ line("""
				{"v":"good","p":"response/content"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals(1, result.events().size());
		assertEquals("good", result.events().getFirst().content());
	}

	@Test
	void emptyStream() {
		DeepSeekEventParser.Result result = parse("");

		assertTrue(result.events().isEmpty());
		assertNull(result.status());
		assertEquals(0, result.responseMessageId());
	}

	@Test
	void contentFilterStatus() {
		String sse = line("""
				{"v":"partial","p":"response/content"}""")
				+ line("""
				{"v":"CONTENT_FILTER","p":"response/status"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals("CONTENT_FILTER", result.status());
	}

	@Test
	void contentFilterCodeField() {
		String sse = line("""
				{"code":"content_filter","v":"blocked","p":""}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals("CONTENT_FILTER", result.status());
	}

	@Test
	void multipleContentChunksAccumulate() {
		String sse = line("""
				{"v":"Hello ","p":"response/content"}""")
				+ line("""
				{"v":"world","p":"response/content"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);

		assertEquals(2, result.events().size());
		assertEquals("Hello ", result.events().getFirst().content());
		assertEquals("world", result.events().get(1).content());
	}

	@Test
	void feedIncrementalChunks() {
		String full = line("""
				{"v":"chunk1","p":"response/content"}""")
				+ line("""
				{"v":"chunk2","p":"response/content"}""")
				+ done();
		byte[] bytes = full.getBytes(StandardCharsets.UTF_8);

		DeepSeekEventParser parser = new DeepSeekEventParser();
		// Feed byte by byte
		for (byte b : bytes) {
			parser.feed(new byte[]{b});
		}
		DeepSeekEventParser.Result result = parser.finish();

		assertEquals(2, result.events().size());
		assertEquals("chunk1", result.events().getFirst().content());
		assertEquals("chunk2", result.events().get(1).content());
	}

	@Test
	void parseStreamWithIOException() {
		InputStream broken = new InputStream() {
			@Override
			public int read() throws java.io.IOException {
				throw new java.io.IOException("broken");
			}
		};
		DeepSeekEventParser.Result result = DeepSeekEventParser.parseStream(broken);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void statusWip() {
		String sse = line("""
				{"v":"WIP","p":"response/status"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals("WIP", result.status());
	}

	@Test
	void fragmentNonObjectSkipped() {
		String sse = line("""
				{"v":["not an object",{"type":"RESPONSE","content":"ok"}],"p":"response/fragments","o":"APPEND"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals(1, result.events().size());
		assertEquals("ok", result.events().getFirst().content());
	}

	@Test
	void fragmentEmptyContentSkipped() {
		String sse = line("""
				{"v":[{"type":"RESPONSE","content":""}],"p":"response/fragments","o":"APPEND"}""")
				+ done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void valueEmptyTextSkipped() {
		String sse = line("""
				{"v":"","p":"response/content"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void valueNonTextualNonObjectSkipped() {
		String sse = line("""
				{"v":42,"p":"response/content"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void thinkTagEmptyBeforeAndAfter() {
		String sse = line("""
				{"v":"</think>","p":"response/thinking_content"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void responseMessageIdZeroIgnored() {
		String sse = line("""
				{"response_message_id":0,"v":"x","p":"response/content"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals(0, result.responseMessageId());
	}

	@Test
	void nestedMessageIdZeroIgnored() {
		String sse = line("""
				{"v":{"response":{"message_id":0}},"p":"response"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals(0, result.responseMessageId());
	}

	@Test
	void nestedResponseWithoutMessageId() {
		String sse = line("""
				{"v":{"response":{"other":"field"}},"p":"response"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals(0, result.responseMessageId());
	}

	@Test
	void statusPathShortForm() {
		String sse = line("""
				{"v":"FINISHED","p":"status"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals("FINISHED", result.status());
	}

	@Test
	void unknownStatusIgnored() {
		String sse = line("""
				{"v":"UNKNOWN_STATUS","p":"response/status"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertNull(result.status());
	}

	@Test
	void fragmentWithoutTypeField() {
		String sse = line("""
				{"v":[{"content":"no type here"}],"p":"response/fragments","o":"APPEND"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals(1, result.events().size());
		assertEquals("content", result.events().getFirst().type());
		assertEquals("no type here", result.events().getFirst().content());
	}

	@Test
	void valueArraySkipped() {
		String sse = line("""
				{"v":["a","b"],"p":"response/content"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void codeFieldNotContentFilter() {
		String sse = line("""
				{"code":"other_error","v":"err","p":""}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertNull(result.status());
	}

	@Test
	void chunkWithoutVField() {
		String sse = line("""
				{"p":"response/content"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void fragmentWithTypeButNoContentField() {
		String sse = line("""
				{"v":[{"type":"THINKING"}],"p":"response/fragments","o":"APPEND"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void textValueOnUnknownPathIgnored() {
		String sse = line("""
				{"v":"ignored text","p":"some/other/path"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void chunkWithoutPField() {
		String sse = line("""
				{"v":"no path"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals(1, result.events().size());
		assertEquals("reasoning", result.events().getFirst().type());
		assertEquals("no path", result.events().getFirst().content());
	}

	@Test
	void fragmentsPathWrongOp() {
		String sse = line("""
				{"v":[{"type":"RESPONSE","content":"x"}],"p":"response/fragments","o":"SET"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void fragmentsPathNoOp() {
		String sse = line("""
				{"v":[{"type":"RESPONSE","content":"x"}],"p":"response/fragments"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void fragmentsPathValueNotArray() {
		String sse = line("""
				{"v":"not array","p":"response/fragments","o":"APPEND"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void fragmentTypeThink() {
		String sse = line("""
				{"v":[{"type":"THINK","content":"thought"}],"p":"response/fragments","o":"APPEND"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertEquals(1, result.events().size());
		assertEquals("reasoning", result.events().getFirst().type());
		assertEquals("thought", result.events().getFirst().content());
	}

	@Test
	void statusPathValueNullSkipped() {
		String sse = line("""
				{"p":"response/status"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertNull(result.status());
	}

	@Test
	void objectValueWithoutNestedResponse() {
		String sse = line("""
				{"v":{"other":"data"},"p":"response/content"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void fragmentsPathAppendWithNullV() {
		String sse = line("""
				{"p":"response/fragments","o":"APPEND"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertTrue(result.events().isEmpty());
	}

	@Test
	void statusPathWithNonTextValue() {
		String sse = line("""
				{"v":123,"p":"response/status"}""") + done();
		DeepSeekEventParser.Result result = parse(sse);
		assertNull(result.status());
	}

	private DeepSeekEventParser.Result parse(String sse) {
		InputStream stream = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
		return DeepSeekEventParser.parseStream(stream);
	}

	private String line(String json) {
		return "data: " + json + "\n\n";
	}

	private String done() {
		return "data: [DONE]\n\n";
	}
}
