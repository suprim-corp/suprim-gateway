package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexRequestConverterTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	@Test
	void toolCallsBecomeFunctionCallItems() {
		Message assistant = Message.builder()
		                           .role("assistant")
		                           .content("calling")
		                           .toolCalls(List.of(
				                           Message.ToolCall.builder()
				                                           .id("call_1")
				                                           .type("function")
				                                           .function(
						                                           Message.Function.builder()
						                                                           .name("lookup")
						                                                           .arguments("{\"q\":\"x\"}")
						                                                           .build()
				                                           )
				                                           .build()
		                           ))
		                           .build();
		Message tool = Message.builder()
		                      .role("tool")
		                      .toolCallId("call_1")
		                      .content("result")
		                      .build();

		ArrayNode input = CodexRequestConverter.toInput(List.of(
				Message.of("user", "hi"),
				assistant,
				tool
		));

		assertEquals(4, input.size());
		assertEquals("message", input.get(0).get("type").asString());
		assertEquals("user", input.get(0).get("role").asString());
		assertEquals("message", input.get(1).get("type").asString());
		assertEquals("assistant", input.get(1).get("role").asString());
		assertEquals("function_call", input.get(2).get("type").asString());
		assertEquals("call_1", input.get(2).get("call_id").asString());
		assertEquals("lookup", input.get(2).get("name").asString());
		assertEquals("{\"q\":\"x\"}", input.get(2).get("arguments").asString());
		assertFalse(input.get(2).has("tool_calls"));
		assertEquals("function_call_output", input.get(3).get("type").asString());
		assertEquals("call_1", input.get(3).get("call_id").asString());
		assertEquals("result", input.get(3).get("output").asString());
	}

	@Test
	void toolsUseFlatResponsesShape() {
		ObjectNode params = MAPPER.createObjectNode();
		params.put("type", "object");
		params.putObject("properties");
		List<Tool> tools = List.of(
				Tool.builder()
				    .type("function")
				    .function(Tool.Function.builder()
				                           .name("lookup")
				                           .description("look up")
				                           .parameters(params)
				                           .build())
				    .build()
		);

		ArrayNode out = CodexRequestConverter.toTools(tools);
		assertEquals(1, out.size());
		JsonNode t = out.get(0);
		assertEquals("function", t.get("type").asString());
		assertEquals("lookup", t.get("name").asString());
		assertTrue(t.has("parameters"));
		assertFalse(t.has("function"));
	}

	@Test
	void payloadHasNoThinkingOrMessages() {
		ObjectNode payload = CodexRequestConverter.toPayload(
				"gpt-5.6-terra",
				List.of(Message.of("user", "hi")),
				List.of(),
				true
		);
		assertEquals("gpt-5.6-terra", payload.get("model").asString());
		assertTrue(payload.has("input"));
		assertFalse(payload.has("messages"));
		assertFalse(payload.has("thinking"));
	}

	@Test
	void systemMessagesLiftToInstructions() {
		ObjectNode payload = CodexRequestConverter.toPayload(
				"gpt-5.6-terra",
				List.of(
						Message.of("system", "be brief"),
						Message.of("developer", "json only"),
						Message.of("user", "hi")
				),
				List.of(),
				true
		);
		assertEquals("be brief\n\njson only", payload.get("instructions").asString());
		ArrayNode input = (ArrayNode) payload.get("input");
		assertEquals(1, input.size());
		assertEquals("user", input.get(0).get("role").asString());
		assertFalse(input.get(0).get("role").asString().equals("system"));
	}
}
