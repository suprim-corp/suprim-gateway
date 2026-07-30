package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingEventWriterTest {

	@Test
	void anthropicStartUsesEarlyInputAndCacheUsage() throws Exception {
		StringWriter output = new StringWriter();
		StreamingEventWriter writer = anthropicWriter(output, 17);

		writer.write(KiroEvent.usage(23, null, 5, 7, null));
		writer.write(KiroEvent.content("hi"));
		writer.finish(42);

		List<JsonNode> events = anthropicEvents(output.toString());
		JsonNode startUsage = events.getFirst().at("/message/usage");
		JsonNode deltaUsage = events.stream()
		                                .filter(event -> "message_delta".equals(
				                                event.path("type").asString()
		                                ))
		                                .findFirst()
		                                .orElseThrow()
		                                .get("usage");

		assertEquals(23, startUsage.get("input_tokens").asInt());
		assertEquals(5, startUsage.get("cache_read_input_tokens").asInt());
		assertEquals(7, startUsage.get("cache_creation_input_tokens").asInt());
		assertEquals(42, deltaUsage.get("output_tokens").asInt());
		assertFalse(deltaUsage.has("input_tokens"));
		assertFalse(deltaUsage.has("cache_read_input_tokens"));
		assertFalse(deltaUsage.has("cache_creation_input_tokens"));
	}

	@Test
	void anthropicLateInputUsageNeverLeaksIntoDelta() throws Exception {
		StringWriter output = new StringWriter();
		StreamingEventWriter writer = anthropicWriter(output, 17);

		writer.write(KiroEvent.content("hi"));
		writer.write(KiroEvent.usage(23, 41, 5, 7, null));
		writer.finish(41);

		List<JsonNode> events = anthropicEvents(output.toString());
		JsonNode startUsage = events.getFirst().at("/message/usage");
		JsonNode deltaUsage = events.stream()
		                                .filter(event -> "message_delta".equals(
				                                event.path("type").asString()
		                                ))
		                                .findFirst()
		                                .orElseThrow()
		                                .get("usage");

		assertEquals(17, startUsage.get("input_tokens").asInt());
		assertFalse(startUsage.has("cache_read_input_tokens"));
		assertEquals(41, deltaUsage.get("output_tokens").asInt());
		assertFalse(deltaUsage.has("input_tokens"));
		assertFalse(deltaUsage.has("cache_read_input_tokens"));
	}

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

	private static StreamingEventWriter anthropicWriter(
			StringWriter output,
			int inputTokens
	) {
		return new StreamingEventWriter(
				new PrintWriter(output),
				new StreamConverter(),
				Format.ANTHROPIC,
				"claude-opus-5",
				true,
				inputTokens
		);
	}

	private static List<JsonNode> anthropicEvents(String stream) throws Exception {
		JsonMapper mapper = new JsonMapper();
		List<JsonNode> events = new ArrayList<>();
		for (String line : stream.lines().toList()) {
			if (line.startsWith("data: {") && !line.contains("message_stop")) {
				events.add(mapper.readTree(line.substring(6)));
			}
		}
		return events;
	}
}
