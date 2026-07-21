package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexSseMapperTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	@Test
	void textDelta() {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", "response.output_text.delta");
		node.put("delta", "hi");
		Optional<KiroEvent> event = CodexSseMapper.toEvent(node);
		assertTrue(event.isPresent());
		assertEquals("content", event.get().type());
		assertEquals("hi", event.get().content());
	}

	@Test
	void functionCallDoneBecomesToolUse() {
		ObjectNode item = MAPPER.createObjectNode();
		item.put("type", "function_call");
		item.put("call_id", "call_1");
		item.put("name", "lookup");
		item.put("arguments", "{\"q\":\"x\"}");

		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", "response.output_item.done");
		node.set("item", item);

		Optional<KiroEvent> event = CodexSseMapper.toEvent(node);
		assertTrue(event.isPresent());
		assertEquals("tool_use", event.get().type());
		assertEquals("lookup", event.get().toolName());
		assertEquals("{\"q\":\"x\"}", event.get().toolInput());
		assertEquals("call_1", event.get().toolUseId());
		assertTrue(event.get().toolStop());
	}

	@Test
	void reasoningDelta() {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", "response.reasoning_summary_text.delta");
		node.put("delta", "think");
		Optional<KiroEvent> event = CodexSseMapper.toEvent(node);
		assertTrue(event.isPresent());
		assertEquals("reasoning", event.get().type());
		assertEquals("think", event.get().content());
	}

	@Test
	void completedUsage() {
		ObjectNode usage = MAPPER.createObjectNode();
		usage.put("input_tokens", 11);
		usage.put("output_tokens", 22);
		ObjectNode response = MAPPER.createObjectNode();
		response.set("usage", usage);
		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", "response.completed");
		node.set("response", response);

		assertEquals(Optional.of(22), CodexSseMapper.usageOutputTokens(node));
		assertEquals(Optional.of(11), CodexSseMapper.usageInputTokens(node));
	}
}
