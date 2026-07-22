package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingEventWriterTest {

	@Test
	void anthropicFinaleIncludesOutputTokens() throws Exception {
		StringWriter output = new StringWriter();
		StreamingEventWriter writer = new StreamingEventWriter(
				new PrintWriter(output),
				new StreamConverter(),
				Format.ANTHROPIC,
				"gpt-5.6-terra",
				true,
				17
		);

		writer.write(dev.suprim.gateway.proxy.kiro.KiroEvent.content("hi"));
		writer.finish(42);

		assertTrue(output.toString().contains("\"input_tokens\":17"));
		assertTrue(output.toString().contains("\"output_tokens\":42"));
	}
}
