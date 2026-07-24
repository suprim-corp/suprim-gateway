package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

		writer.write(KiroEvent.content("hi"));
		writer.finish(42);

		assertTrue(output.toString().contains("\"input_tokens\":17"));
		assertTrue(output.toString().contains("\"output_tokens\":42"));
	}

	@Test
	void responsesFinaleIncludesUsage() throws Exception {
		StringWriter output = new StringWriter();
		StreamingEventWriter writer = new StreamingEventWriter(
				new PrintWriter(output),
				new StreamConverter(),
				Format.RESPONSES,
				"gpt-5.6-terra",
				true,
				17
		);

		writer.write(KiroEvent.content("hi"));
		writer.finish(42);

		assertTrue(output.toString().contains("\"input_tokens\":17"));
		assertTrue(output.toString().contains("\"output_tokens\":42"));
		assertTrue(output.toString().contains("\"total_tokens\":59"));
	}

	@Test
	void completionFinaleIncludesUsageOnlyOnFinishChunk() throws Exception {
		StringWriter output = new StringWriter();
		StreamingEventWriter writer = new StreamingEventWriter(
				new PrintWriter(output),
				new StreamConverter(),
				Format.COMPLETION,
				"gpt-5.6-terra",
				true,
				17
		);

		writer.write(KiroEvent.content("hi"));
		writer.finish(42);

		String streamed = output.toString();
		int usageIndex = streamed.indexOf("\"usage\"");
		int finishIndex = streamed.indexOf("\"finish_reason\":\"stop\"");
		assertTrue(usageIndex > finishIndex);
		assertTrue(streamed.contains("\"prompt_tokens\":17"));
		assertTrue(streamed.contains("\"completion_tokens\":42"));
		assertTrue(streamed.contains("\"total_tokens\":59"));
		assertFalse(streamed.substring(0, finishIndex).contains("\"usage\""));
	}
}
